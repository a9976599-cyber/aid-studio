package com.aid.media.provider.impl;

import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.util.ModelCapabilityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** Topaz Image API adapter. Model identity, capability and price remain model configuration. */
@Slf4j
@Component
public class TopazImageProviderClient implements ImageProviderClient {
    private static final String PROTOCOL = "topaz-image";
    private static final String PROVIDER = "topaz";
    private static final String DEFAULT_BASE = "https://api.topazlabs.com";
    private static final String STATUS_PATH = "/image/v1/status/%s";
    private static final String DOWNLOAD_PATH = "/image/v1/download/%s";
    private static final Set<String> IMAGE_PATHS = Set.of(
            "/image/v1/enhance/async", "/image/v1/enhance-gen/async");
    private static final Set<String> ALLOWED_OPTIONS = Set.of(
            "referenceImages", "images", "providerParameters", "n", "force_single");
    private static final Pattern SIZE = Pattern.compile("^(\\d{1,5})[xX*×](\\d{1,5})$");
    private static final int HTTP_TIMEOUT_MS = 30_000;

    @Override
    public String protocol() { return PROTOCOL; }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return PROVIDER.equalsIgnoreCase(StringUtils.trimToEmpty(providerCode));
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) { return 1; }

    @Override
    public void validateRequest(AiModelConfigVo config, MediaImageGenerateRequest request) {
        check(config, request, true);
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo config, MediaImageGenerateRequest request) {
        // The shared input layer already validated the original URL. The media task signs it
        // immediately before submit, so do not apply the original-URL syntax check a second time.
        Prepared prepared = check(config, request, false);
        String url = ProviderEndpointUtils.buildSubmitUrl(base(config), prepared.path());
        HttpRequest post = HttpRequest.post(url).header("X-API-Key", config.getApiKey())
                .contentType(ContentType.MULTIPART.getValue())
                .form("source_url", prepared.sourceUrl())
                .form("model", prepared.model())
                .form("output_width", prepared.width())
                .form("output_height", prepared.height())
                .timeout(HTTP_TIMEOUT_MS);
        if (StringUtils.isNotBlank(request.getPrompt())) post.form("prompt", request.getPrompt());
        appendOptions(post, config, request);
        try (HttpResponse response = post.execute()) {
            if (!response.isOk()) {
                log.warn("Topaz 图片提交失败: modelCode={}, httpStatus={}", config.getModelCode(), response.getStatus());
                throw new ServiceException("Topaz 提交失败");
            }
            JsonNode json = ProviderResponseHelper.readTree(response.body());
            String taskId = ProviderResponseHelper.readText(json, "process_id");
            if (StringUtils.isBlank(taskId)) {
                log.warn("Topaz 图片提交缺少 process_id: modelCode={}", config.getModelCode());
                throw new ServiceException("Topaz 响应无任务编号");
            }
            return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(response.body()).build();
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Topaz 图片提交网络异常: modelCode={}, type={}", config.getModelCode(), ex.getClass().getSimpleName());
            throw new ServiceException("Topaz 连接暂不可用");
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo config, String taskId) {
        if (config == null || StringUtils.isBlank(config.getApiKey()) || StringUtils.isBlank(taskId)) {
            return anomaly(null, null);
        }
        try {
            JsonNode status;
            String statusBody;
            try (HttpResponse response = authenticatedGet(config, STATUS_PATH, taskId)) {
                if (!response.isOk()) return anomaly(null, null);
                statusBody = response.body();
                status = ProviderResponseHelper.readTree(statusBody);
            }
            String upstream = ProviderResponseHelper.readText(status, "status");
            if (upstream == null) return anomaly(null, null);
            String normalized = upstream.toLowerCase(Locale.ROOT);
            if ("pending".equals(normalized) || "processing".equals(normalized)) {
                return ProviderTaskResult.builder().status("PROCESSING")
                        .providerStatus(upstream).progress(ProviderResponseHelper.readInt(status, "progress"))
                        .rawResponse(statusBody).querySuccessful(true).terminalConfirmed(false).build();
            }
            if ("failed".equals(normalized) || "cancelled".equals(normalized)) {
                String detail = ProviderResponseHelper.readText(status, "error.message", "message", "detail");
                return ProviderTaskResult.builder().status("FAILED").providerStatus(upstream)
                        .errorMessage("Topaz 处理失败")
                        .rawErrorMessage(ProviderErrorSanitizer.safeMessage(detail, "Topaz 处理失败"))
                        .rawResponse(statusBody).querySuccessful(true).terminalConfirmed(true).build();
            }
            if (!"completed".equals(normalized)) return anomaly(upstream, null);
            // The status endpoint does not guarantee a usable output URL. The dedicated
            // download endpoint returns an expiring URL; the existing media task copies it to OSS.
            try (HttpResponse response = authenticatedGet(config, DOWNLOAD_PATH, taskId)) {
                if (!response.isOk()) return anomaly(upstream, null);
                JsonNode output = ProviderResponseHelper.readTree(response.body());
                String outputUrl = ProviderResponseHelper.readText(output, "download_url");
                if (StringUtils.isBlank(outputUrl) || !outputUrl.startsWith("https://")) {
                    return anomaly(upstream, null);
                }
                return ProviderTaskResult.builder().status("SUCCEEDED").providerStatus(upstream)
                        .resultUrl(outputUrl).resultUrls(List.of(outputUrl)).resultCount(1)
                        .progress(100).rawResponse(statusBody).querySuccessful(true).terminalConfirmed(true).build();
            }
        } catch (Exception ex) {
            log.warn("Topaz 图片查询暂不可用: taskId={}, type={}", taskId, ex.getClass().getSimpleName());
            return anomaly(null, null);
        }
    }

    private static HttpResponse authenticatedGet(AiModelConfigVo config, String path, String taskId) {
        return HttpRequest.get(ProviderEndpointUtils.buildTaskQueryUrl(base(config), path, taskId))
                .header("X-API-Key", config.getApiKey()).timeout(HTTP_TIMEOUT_MS).execute();
    }

    private static String base(AiModelConfigVo config) {
        return StringUtils.defaultIfBlank(config.getBaseUrl(), DEFAULT_BASE);
    }

    private static ProviderTaskResult anomaly(String upstream, String reason) {
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus(upstream)
                .errorMessage(StringUtils.defaultIfBlank(reason, "上游状态暂不可用"))
                .querySuccessful(false).terminalConfirmed(false).build();
    }

    private static Prepared check(AiModelConfigVo config, MediaImageGenerateRequest request,
                                  boolean validateSourceUrl) {
        if (request == null) throw new ServiceException("Topaz 请求不能为空");
        if (config == null || StringUtils.isBlank(config.getApiKey())) {
            throw new ServiceException("Topaz API Key 未配置");
        }
        String path = StringUtils.defaultIfBlank(config.getApiSuffix(), "/image/v1/enhance/async");
        if (!IMAGE_PATHS.contains(path)) throw new ServiceException("Topaz 模型接口路径无效");
        List<String> images = images(request);
        if (images.size() != 1) throw new ServiceException("Topaz 模型需要一张原图");
        String source = images.get(0);
        // The shared verified-media layer probes the source at submission time.
        // Quote and provider preflight must not repeat remote HEAD requests.
        if (validateSourceUrl) {
            var urlValidation = ImageUrlValidator.validateImageUrlFormat(source);
            if (!urlValidation.isValid()) {
                log.warn("Topaz 原图地址校验失败: modelCode={}, reason={}, length={}",
                        config.getModelCode(), urlValidation.getCode(), source.length());
                throw new ServiceException("原图地址无效");
            }
        }
        Matcher size = SIZE.matcher(StringUtils.trimToEmpty(request.getSize()));
        if (!size.matches()) throw new ServiceException("请提供输出宽高");
        int width = Integer.parseInt(size.group(1));
        int height = Integer.parseInt(size.group(2));
        if (width < 1 || width > 32000 || height < 1 || height > 32000) {
            throw new ServiceException("输出宽高超限");
        }
        JsonNode caps = ModelCapabilityResolver.parseCapability(config.getCapabilityJson());
        long maxPixels = caps == null ? 0 : caps.path("maxOutputPixels").asLong(0);
        if (maxPixels <= 0 || (long) width * height > maxPixels) {
            throw new ServiceException("输出像素超出模型配置");
        }
        if (request.getExpectedImageCount() != null && request.getExpectedImageCount() != 1) {
            throw new ServiceException("Topaz 每次只能输出一张图");
        }
        if (StringUtils.isNotBlank(request.getPrompt()) && !Boolean.TRUE.equals(config.getSupportsTextInput())) {
            throw new ServiceException("该模型不支持提示词");
        }
        if (StringUtils.isNotBlank(request.getNegativePrompt())) {
            throw new ServiceException("Topaz 图片增强不支持负向提示词");
        }
        if (request.getOptions() != null && request.getOptions().keySet().stream()
                .anyMatch(key -> !ALLOWED_OPTIONS.contains(key))) {
            throw new ServiceException("Topaz 图片增强不支持所传参数");
        }
        String model = ModelCodeResolver.resolveUpstreamModel(config, request.getModelName());
        if (StringUtils.isBlank(model)) throw new ServiceException("Topaz 模型标识缺失");
        validateOptions(config, request);
        return new Prepared(path, model, source, width, height);
    }

    private static List<String> images(MediaImageGenerateRequest request) {
        if (request == null) return List.of();
        Set<String> values = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(request.getReferenceImageUrl())) values.add(request.getReferenceImageUrl());
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            for (String key : List.of("referenceImages", "images")) {
                if (options.get(key) instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof String text && StringUtils.isNotBlank(text)) values.add(text);
                    }
                }
            }
        }
        return new ArrayList<>(values);
    }

    private static void validateOptions(AiModelConfigVo config, MediaImageGenerateRequest request) {
        Object raw = request.getOptions() == null ? null : request.getOptions().get("providerParameters");
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> parameters)) throw new ServiceException("Topaz 参数格式无效");
        JsonNode caps = ModelCapabilityResolver.parseCapability(config.getCapabilityJson());
        JsonNode schema = caps == null ? null : caps.path("providerParameters");
        for (var entry : parameters.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[a-z][A-Za-z0-9_]{0,63}")
                    || schema == null || !schema.has(key)) {
                throw new ServiceException("Topaz 参数不受模型支持");
            }
            Object value = entry.getValue();
            JsonNode rule = schema.path(key);
            if (value == null || value instanceof Map<?, ?> || value instanceof List<?>
                    || "boolean".equals(rule.path("type").asText()) && !(value instanceof Boolean)
                    || "number".equals(rule.path("type").asText()) && !(value instanceof Number)
                    || "integer".equals(rule.path("type").asText()) && !(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                    || "string".equals(rule.path("type").asText()) && !(value instanceof String)) {
                throw new ServiceException("Topaz 参数类型无效");
            }
            if (value instanceof Number number && (!Double.isFinite(number.doubleValue())
                    || rule.has("min") && number.doubleValue() < rule.path("min").asDouble()
                    || rule.has("max") && number.doubleValue() > rule.path("max").asDouble())) {
                throw new ServiceException("Topaz 参数超出范围");
            }
            if (rule.has("enum") && rule.path("enum").isArray()) {
                boolean found = false;
                for (JsonNode option : rule.path("enum")) {
                    if (option.asText().equals(String.valueOf(value))) found = true;
                }
                if (!found) throw new ServiceException("Topaz 参数值无效");
            }
        }
        if (Boolean.TRUE.equals(parameters.get("faceEnhancement"))
                && (!parameters.containsKey("faceEnhancementStrength")
                    || !parameters.containsKey("faceEnhancementCreativity"))) {
            throw new ServiceException("启用人脸增强时需填写强度与创意度");
        }
    }

    private static void appendOptions(HttpRequest post, AiModelConfigVo config, MediaImageGenerateRequest request) {
        Object raw = request.getOptions() == null ? null : request.getOptions().get("providerParameters");
        if (!(raw instanceof Map<?, ?> parameters)) return;
        JsonNode caps = ModelCapabilityResolver.parseCapability(config.getCapabilityJson());
        JsonNode schema = caps.path("providerParameters");
        parameters.forEach((key, value) -> {
            JsonNode rule = schema.path(String.valueOf(key));
            String upstream = rule.path("upstream").asText(String.valueOf(key));
            post.form(upstream, value);
        });
    }

    private record Prepared(String path, String model, String sourceUrl, int width, int height) { }
}
