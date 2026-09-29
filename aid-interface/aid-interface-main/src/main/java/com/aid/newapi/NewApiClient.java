package com.aid.newapi;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.GatewayUrlUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** New API 普通用户接口。所有请求使用已保存的站点地址，禁止重定向携带凭证。 */
@Slf4j
@Component
public class NewApiClient {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public JsonNode get(AidAiProvider provider, String path, boolean account) {
        return request(provider, path, account, null);
    }

    /** The upstream may disable its pricing page while keeping model relay available. */
    public JsonNode getOptionalPricing(AidAiProvider provider) {
        return request(provider, "/api/pricing", true, null, Duration.ofSeconds(30), true);
    }

    public JsonNode post(AidAiProvider provider, String path, Object body) {
        return request(provider, path, true, body);
    }

    public JsonNode postModel(AidAiProvider provider, String path, Object body) {
        return request(provider, path, false, body, Duration.ofMinutes(3));
    }

    private JsonNode request(AidAiProvider provider, String path, boolean account, Object body) {
        return request(provider, path, account, body, Duration.ofSeconds(30));
    }

    private JsonNode request(AidAiProvider provider, String path, boolean account, Object body, Duration timeout) {
        return request(provider, path, account, body, timeout, false);
    }

    private JsonNode request(AidAiProvider provider, String path, boolean account, Object body,
                             Duration timeout, boolean optionalPricing) {
        if (!GatewayUrlUtils.isBaseGatewayUrl(provider.getBaseUrl()) || !path.startsWith("/")
                || path.startsWith("//") || path.contains("#")) {
            log.info("New API 站点请求地址无效, providerId={}", provider.getId());
            throw new ServiceException("站点地址无效");
        }
        if (account && !Boolean.TRUE.equals(provider.getNewApiSystemTokenEnabled())) {
            log.info("New API 账户授权未开启, providerId={}", provider.getId());
            throw new ServiceException("账户授权未开启");
        }
        String key = account ? provider.getNewApiAccessToken() : provider.getApiKey();
        if (StrUtil.isBlank(key)) {
            log.info("New API 凭证未配置, providerId={}, account={}", provider.getId(), account);
            throw new ServiceException("站点凭证未配置");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            GatewayUrlUtils.normalizeBaseGatewayUrl(provider.getBaseUrl()) + path))
                    .timeout(timeout).header("Authorization", "Bearer " + key.trim())
                    .header("Accept", "application/json");
            if (account && provider.getNewApiUserId() != null && provider.getNewApiUserId() > 0) {
                builder.header("New-Api-User", provider.getNewApiUserId().toString());
            }
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (optionalPricing && (response.statusCode() == 403 || response.statusCode() == 404)) {
                    log.info("New API price catalog unavailable; using configured default, providerId={}, status={}",
                            provider.getId(), response.statusCode());
                    return null;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.info("New API 请求失败, providerId={}, status={}, account={}",
                            provider.getId(), response.statusCode(), account);
                    throw new ServiceException(response.statusCode() == 401 || response.statusCode() == 403
                            ? "上游授权被拒绝" : "上游接口不可用");
                }
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("response too large");
                JsonNode root = mapper.readTree(bytes);
                if (root == null || !root.isObject() || (root.has("success") && !root.path("success").asBoolean())
                        || root.has("error")) {
                    log.info("New API 返回业务失败, providerId={}, account={}", provider.getId(), account);
                    throw new ServiceException("上游请求未成功");
                }
                return root;
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("New API 请求中断, providerId={}", provider.getId());
            throw new ServiceException("上游请求已中断");
        } catch (Exception ex) {
            // 不记录响应正文、请求头或异常消息，其中可能包含访问令牌。
            log.warn("New API 请求异常, providerId={}, type={}", provider.getId(), ex.getClass().getSimpleName());
            throw new ServiceException("上游连接失败");
        }
    }
}
