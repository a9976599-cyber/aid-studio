package com.aid.config.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.impl.GenericOpenAiCompatibleTextProviderClient;
import com.aid.newapi.NewApiImageProviderClient;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 模型连通性测试器（testKey = ai-model）。
 */
@Slf4j
@Component
public class AiModelConnectivityTester implements ConfigConnectivityTester {

    /** payload 中模型主键字段名 */
    private static final String PAYLOAD_MODEL_ID = "modelId";

    private final IAidAiModelService modelService;

    private final IAidAiProviderService providerService;

    /** protocol → Probe 映射（Spring 注入所有 Probe 实现构建） */
    private final Map<String, ProviderProbe> probeMap = new HashMap<>();

    /** providerCode → Probe 映射（厂商元数据探测优先于通用协议回退） */
    private final Map<String, ProviderProbe> providerProbeMap = new HashMap<>();

    private IAiModelConfigService modelConfigurations;
    private GenericOpenAiCompatibleTextProviderClient newApiTextClient;
    private NewApiImageProviderClient newApiImageClient;

    @org.springframework.beans.factory.annotation.Autowired
    public void setNewApiGenerationTestDependencies(IAiModelConfigService modelConfigurations,
            @org.springframework.beans.factory.annotation.Qualifier("genericOpenAiCompatibleTextProviderClient")
            GenericOpenAiCompatibleTextProviderClient newApiTextClient,
            NewApiImageProviderClient newApiImageClient) {
        this.modelConfigurations = modelConfigurations;
        this.newApiTextClient = newApiTextClient;
        this.newApiImageClient = newApiImageClient;
    }

    /**
     * 构造器注入：模型 / 服务商服务 + 所有探活实现。
     *
     * @param modelService    模型服务
     * @param providerService 服务商服务
     * @param probeList       全部 Provider 探活实现（可能为空）
     */
    public AiModelConnectivityTester(IAidAiModelService modelService,
                                     IAidAiProviderService providerService,
                                     List<ProviderProbe> probeList) {
        this.modelService = modelService;
        this.providerService = providerService;
        if (probeList != null) {
            for (ProviderProbe probe : probeList) {
                if (StrUtil.isNotBlank(probe.protocol())) {
                    this.probeMap.put(probe.protocol(), probe);
                }
                if (StrUtil.isNotBlank(probe.providerCode())) {
                    this.providerProbeMap.put(probe.providerCode(), probe);
                }
            }
        }
    }

    @Override
    public String testKey() {
        return "ai-model";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        try {
            Long modelId = extractModelId(request);
            if (modelId == null) {
                return ConfigTestResult.fail("缺少模型参数");
            }
            AidAiModel model = modelService.selectAidAiModelById(modelId);
            if (model == null) {
                log.error("AI 模型探活失败: 模型不存在, modelId={}", modelId);
                return ConfigTestResult.fail("模型不存在");
            }
            if (model.getProviderId() == null) {
                log.error("AI 模型探活失败: 模型未绑定服务商, modelId={}", modelId);
                return ConfigTestResult.fail("模型未绑定服务商");
            }
            AidAiProvider provider = providerService.selectAidAiProviderById(model.getProviderId());
            if (provider == null) {
                log.error("AI 模型探活失败: 服务商不存在, providerId={}", model.getProviderId());
                return ConfigTestResult.fail("服务商不存在");
            }
            if ("NEW_API".equals(provider.getIntegrationType())
                    && "openai-compatible-text".equals(model.getProtocol())) {
                return testNewApiTextGeneration(model, provider);
            }
            if ("NEW_API".equals(provider.getIntegrationType())
                    && "newapi-image".equals(model.getProtocol())) {
                return testNewApiImageGeneration(model, provider);
            }
            String providerCode = provider.getProviderCode();
            ProviderProbe probe = providerProbeMap.get(providerCode);
            if (probe != null && !probe.supportsModel(model)) {
                probe = null;
            }
            if (probe == null && StrUtil.isNotBlank(model.getProtocol())) {
                probe = probeMap.get(model.getProtocol());
            }
            ProbeResult probeResult;
            if (probe != null) {
                // 厂商元数据查询优先，通用协议仅做安全回退
                probeResult = probe.probe(model, provider);
            } else {
                // 退化结果只代表网关可达，不代表密钥或模型有效
                if (StrUtil.isBlank(provider.getApiKey())) {
                    log.error("AI 模型探活失败: 未配置密钥, providerCode={}", providerCode);
                    return ConfigTestResult.fail("未配置密钥");
                }
                probeResult = ProviderConnectivitySupport.checkBaseUrl(provider.getBaseUrl(), providerCode);
            }
            return toTestResult(probeResult, providerCode);
        } catch (Exception e) {
            // 兜底：禁止异常冒泡到前端
            log.error("AI 模型探活异常, testKey={}", testKey(), e);
            return buildFail("测试执行失败", e);
        }
    }

    /** New API 文本模型实际发起最小生成，只有收到助手正文才判定可用。 */
    private ConfigTestResult testNewApiTextGeneration(AidAiModel model, AidAiProvider provider) {
        if (modelConfigurations == null || newApiTextClient == null) return ConfigTestResult.fail("实际生成测试不可用");
        AiModelConfigVo config = modelConfigurations.selectByModelId(model.getId());
        if (config == null || StrUtil.isBlank(config.getApiKey())) return ConfigTestResult.fail("模型调用密钥未配置");
        MediaTextGenerateRequest request = new MediaTextGenerateRequest();
        request.setModelName(model.getModelCode());
        request.setPrompt("Reply with OK.");
        request.setOptions(Map.of("max_completion_tokens", 32));
        ProviderSubmitResult response = newApiTextClient.chatSync(config, request);
        if (response == null || StrUtil.isBlank(response.getDirectText())) {
            return ConfigTestResult.fail("上游未返回有效文本，请核对模型 Key、分组和额度");
        }
        ConfigTestResult result = ConfigTestResult.ok("实际生成成功", provider.getProviderCode());
        result.setDetails("已通过本站配置向上游发起文本生成；返回了助手正文。该测试会消耗少量上游额度。");
        return result;
    }

    /** 与正式图片任务共用协议客户端，生成并保存一张图片后才判定可用。 */
    private ConfigTestResult testNewApiImageGeneration(AidAiModel model, AidAiProvider provider) {
        if (modelConfigurations == null || newApiImageClient == null) return ConfigTestResult.fail("实际生成测试不可用");
        AiModelConfigVo config = modelConfigurations.selectByModelId(model.getId());
        if (config == null || StrUtil.isBlank(config.getApiKey())) return ConfigTestResult.fail("模型调用密钥未配置");
        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setModelName(model.getModelCode());
        request.setPrompt("A small blue dot on a white background");
        request.setSize("1024x1024");
        request.setExpectedImageCount(1);
        ProviderSubmitResult response = newApiImageClient.submit(config, request);
        if (response == null || (StrUtil.isBlank(response.getDirectUrl()) && StrUtil.isBlank(response.getOssUrl()))) {
            return ConfigTestResult.fail("上游未返回可保存的图片，请核对模型 Key、存储配置和额度");
        }
        ConfigTestResult result = ConfigTestResult.ok("实际生成成功", provider.getProviderCode());
        result.setDetails("已按正式图片协议向上游生成一张图片并完成结果保存。该测试会消耗上游额度。");
        return result;
    }

    /**
     * 从 payload 解析 modelId。
     */
    private Long extractModelId(ConfigTestRequest request) {
        if (request == null || request.getPayload() == null) {
            return null;
        }
        Object raw = request.getPayload().get(PAYLOAD_MODEL_ID);
        return Convert.toLong(raw, null);
    }

    /**
     * 把探活结果映射为统一测试结果。
     */
    private ConfigTestResult toTestResult(ProbeResult probeResult, String providerCode) {
        if (probeResult != null && probeResult.isOk()) {
            ConfigTestResult result = ConfigTestResult.ok(probeResult.getMessage(), providerCode);
            result.setDetails(probeResult.getDetail());
            return result;
        }
        ConfigTestResult result = ConfigTestResult.fail(
                probeResult == null ? "测试失败" : probeResult.getMessage());
        if (probeResult != null) {
            result.setDetails(probeResult.getDetail());
        }
        result.setProvider(providerCode);
        return result;
    }

    /**
     * 构造失败结果（堆栈进 details，无密钥明文）。
     */
    private ConfigTestResult buildFail(String message, Exception e) {
        ConfigTestResult result = ConfigTestResult.fail(message);
        result.setDetails(e.getClass().getSimpleName() + ": " + e.getMessage());
        return result;
    }
}
