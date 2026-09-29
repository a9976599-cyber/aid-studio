package com.aid.newapi;

import com.aid.aid.domain.AidAiModel;
import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSku;
import com.aid.billing.model.SettleRule;
import com.aid.model.definition.LegacyModelDefinitionConverter;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 优先换算站点计价；无法换算时按平台明确的默认价格生成 SKU。 */
@Component
public class NewApiModelCompiler {
    // New API plugins/tasks/sora/plugin.js declares these as video models even if pricing is hidden.
    private static final Set<String> SORA_VIDEO_MODELS = Set.of("sora-2", "sora-2-pro");

    public record Preview(String modelId, AidAiModel model, String fingerprint, List<String> blockers,
                          String pricingSource, String pricingLabel) { }

    public Preview compile(long providerId, NewApiService.Model entry, BigDecimal groupRatio,
                           BigDecimal usdToCny, BigDecimal quotaPerUnit) {
        JsonNode pricing = entry.pricing();
        // 部分站点将按秒计价的媒体模型标成 openai；不能误按文本 Token 模型导入。
        if (SORA_VIDEO_MODELS.contains(entry.id()) || entry.endpoints().contains("openai-video")
                || (pricing != null && pricing.path("billing_usage_schema").has("seconds"))) {
            return new Preview(entry.id(), null, null, List.of("按秒计费媒体协议尚未完成实际调用验证"),
                    "DEFAULT", "默认 ¥1/秒");
        }
        if (entry.endpoints().contains("image-generation")) return compileImage(providerId, entry);
        if (!entry.endpoints().contains("openai")) return blocked(entry, "尚未匹配此接口的执行协议");
        boolean ratio = pricing != null && (pricing.path("billing_mode").asText().isBlank()
                || "ratio".equals(pricing.path("billing_mode").asText()))
                && pricing.path("quota_type").asInt(-1) == 0
                && nonnegative(pricing.get("model_ratio")) && nonnegative(pricing.get("completion_ratio"))
                && groupRatio != null && groupRatio.signum() >= 0 && usdToCny != null && usdToCny.signum() > 0
                && quotaPerUnit != null && quotaPerUnit.signum() > 0;
        BigDecimal input = ratio ? pricing.get("model_ratio").decimalValue().multiply(BigDecimal.valueOf(1000000))
                .divide(quotaPerUnit, 12, java.math.RoundingMode.HALF_UP).multiply(groupRatio).multiply(usdToCny)
                : BigDecimal.ONE;
        BillingSku sku = new BillingSku();
        sku.setSkuCode("newapi_tokens"); sku.setSkuName("文本 Token"); sku.setEnabled(true); sku.setPriority(1);
        sku.setMeterType("TOKEN"); sku.setMatch(Map.of()); sku.setInputPricePerMillion(input);
        sku.setOutputPricePerMillion(ratio ? input.multiply(pricing.get("completion_ratio").decimalValue()) : BigDecimal.ONE);
        if (ratio && nonnegative(pricing.get("cache_ratio"))) sku.setCachedInputPricePerMillion(input.multiply(pricing.get("cache_ratio").decimalValue()));
        if (ratio && nonnegative(pricing.get("create_cache_ratio"))) sku.setCacheWritePricePerMillion(input.multiply(pricing.get("create_cache_ratio").decimalValue()));
        sku.setRemark(ratio ? "来自站点价格与所选分组倍率" : "站点价格缺失或无法换算，默认每百万输入及输出 Token 各 1 元");
        BillingRule rule = new BillingRule(); rule.setMode("SKU"); rule.setMeterType("TOKEN");
        rule.setPreHold(true); rule.setMatchStrategy("FIRST_HIT"); rule.setSkus(List.of(sku)); rule.setParams(List.of());
        SettleRule settle = new SettleRule(); settle.setUsageSource("PROVIDER_USAGE"); settle.setUsagePricingMode("BUCKETED");
        settle.setSettleMode("REFUND_ONLY"); settle.setAllowRefund(true); settle.setAllowExtraCharge(false); settle.setCharToTokenRatio(2);
        rule.setSettleRule(settle);

        AidAiModel model = new AidAiModel(); model.setProviderId(providerId); model.setRealModelCode(entry.id());
        model.setModelCode("newapi_" + providerId + "_" + digest(entry.id()).substring(0, 20));
        model.setModelName(entry.id()); model.setModelType("text"); model.setGenerateMode("text");
        model.setProtocol("openai-compatible-text"); model.setApiSuffix("/v1/chat/completions");
        model.setStatus("0"); model.setDelFlag("0"); model.setBillingMode("SKU"); model.setBillingRuleJson(JSON.toJSONString(rule));
        model.setBillingMultiplier(BigDecimal.ONE); model.setIsFree(false); model.setPriority(1);
        model.setSupportsTextInput(true); model.setSupportsSystemPrompt(true); model.setSupportsImageInput(false);
        model.setSupportsMultiImageInput(false); model.setSupportsAspectRatio(false); model.setSupportsSizePreset(false);
        model.setSupportsDuration(false); model.setSupportsFirstFrame(false); model.setSupportsLastFrame(false);
        model.setDefaultOutputCount(1); model.setMaxOutputCount(1); model.setCapabilityJson("{}");
        model.setCapabilities(LegacyModelDefinitionConverter.convert(model));
        model.getCapabilities().forEach(cap -> {
            cap.setEvidenceStatus("VERIFIED");
            cap.setSourceUrls(List.of("https://github.com/QuantumNous/new-api/blob/main/router/relay-router.go"));
            cap.getBindings().forEach(binding -> binding.setCode("newapi_chat"));
        });
        return new Preview(entry.id(), model, digest(JSON.toJSONString(model)), List.of(),
                ratio ? "UPSTREAM" : "DEFAULT", ratio ? "站点 Token 价格" : "默认 ¥1/百万 Token");
    }

    private Preview compileImage(long providerId, NewApiService.Model entry) {
        BillingSku sku = new BillingSku();
        sku.setSkuCode("newapi_image"); sku.setSkuName("图片生成"); sku.setEnabled(true); sku.setPriority(1);
        sku.setMeterType("PER_IMAGE"); sku.setMatch(Map.of()); sku.setPrice(BigDecimal.ONE);
        sku.setRemark("站点价格缺失或无法换算，默认每张 1 元");
        BillingRule rule = new BillingRule(); rule.setMode("SKU"); rule.setChargeType("IMAGE");
        rule.setMeterType("PER_IMAGE"); rule.setPreHold(true); rule.setMatchStrategy("FIRST_HIT");
        rule.setSkus(List.of(sku)); rule.setParams(List.of());
        AidAiModel model = new AidAiModel(); model.setProviderId(providerId); model.setRealModelCode(entry.id());
        model.setModelCode("newapi_" + providerId + "_" + digest(entry.id()).substring(0, 20));
        model.setModelName(entry.id()); model.setModelType("image"); model.setGenerateMode("text_to_image");
        model.setProtocol("newapi-image"); model.setApiSuffix("/v1/images/generations");
        model.setStatus("0"); model.setDelFlag("0"); model.setBillingMode("SKU");
        model.setBillingRuleJson(JSON.toJSONString(rule)); model.setBillingMultiplier(BigDecimal.ONE);
        model.setIsFree(false); model.setPriority(1); model.setSupportsTextInput(true);
        model.setSupportsImageInput(false); model.setSupportsMultiImageInput(false);
        model.setSupportsAspectRatio(false); model.setSupportsSizePreset(true);
        model.setSupportsDuration(false); model.setSupportsFirstFrame(false); model.setSupportsLastFrame(false);
        model.setDefaultOutputCount(1); model.setMaxOutputCount(1);
        model.setCapabilityJson("{\"defaultSize\":\"1024x1024\",\"sizeOptions\":[\"1024x1024\"],\"maxReferenceImages\":0,\"sceneRules\":{\"textToImage\":{\"supportsSizePreset\":true}}}");
        model.setCapabilities(LegacyModelDefinitionConverter.convert(model));
        model.getCapabilities().forEach(cap -> {
            cap.setEvidenceStatus("VERIFIED");
            cap.setSourceUrls(List.of("https://github.com/QuantumNous/new-api/blob/main/router/relay-router.go"));
            cap.getBindings().forEach(binding -> binding.setCode("newapi_image"));
        });
        return new Preview(entry.id(), model, digest(JSON.toJSONString(model)), List.of(), "DEFAULT", "默认 ¥1/张");
    }

    private boolean nonnegative(JsonNode value) { return value != null && value.isNumber() && value.decimalValue().signum() >= 0; }
    private Preview blocked(NewApiService.Model model, String reason) {
        return new Preview(model.id(), null, null, List.of(reason), null, null);
    }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
