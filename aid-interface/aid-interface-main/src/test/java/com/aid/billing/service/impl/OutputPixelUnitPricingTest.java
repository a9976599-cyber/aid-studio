package com.aid.billing.service.impl;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 按输出像素单位计价应在冻结时确定单张价，并由既有单张结算快照沿用。 */
class OutputPixelUnitPricingTest {

    @Test
    void chargesOneUnitAtBoundaryAndRoundsUpAboveBoundary() {
        assertPrice(24_000_000L, "0.67");
        assertPrice(24_000_001L, "1.34");
        assertPrice(1_024_000_000L, "28.81");
    }

    @Test
    void refusesAmbiguousSizeInsteadOfChargingMinimum() {
        BillingCalcResult missing = calculator().calculatePreHoldAmount(model(),
                new BillingInput("IMAGE", Map.of("expectedImageCount", 1)));
        assertFalse(missing.isMatched());
        BillingCalcResult unknown = calculator().calculatePreHoldAmount(model(),
                new BillingInput("IMAGE", Map.of("outputPixels", Long.MAX_VALUE, "expectedImageCount", 1)));
        assertFalse(unknown.isMatched());
    }

    @Test
    void fixedPerImageSkuKeepsExistingBehavior() {
        AiModelConfigVo model = model();
        model.setBillingRuleJson(model.getBillingRuleJson().replace(",\"outputPixelsPerUnit\":24000000", ""));
        BillingCalcResult result = calculator().calculatePreHoldAmount(model,
                new BillingInput("IMAGE", Map.of("expectedImageCount", 2)));
        assertTrue(result.isMatched());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("1.34")));
    }

    private void assertPrice(long pixels, String expected) {
        BillingCalcResult result = calculator().calculatePreHoldAmount(model(),
                new BillingInput("IMAGE", Map.of("outputPixels", pixels, "expectedImageCount", 1)));
        assertTrue(result.isMatched());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal(expected)));
        assertEquals(0, result.getSnapshot().getUnitPrice().compareTo(new BigDecimal(expected)));
    }

    private AiModelConfigVo model() {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("topaz-standard-2");
        model.setModelName("Topaz Standard 2");
        model.setModelType("image");
        model.setBillingMode("SKU");
        model.setBillingMultiplier(BigDecimal.ONE);
        model.setBillingRuleJson("{\"mode\":\"SKU\",\"meterType\":\"PER_IMAGE\",\"skus\":[{"
                + "\"skuCode\":\"TOPAZ_OUTPUT_MP\",\"skuName\":\"输出像素\",\"enabled\":true,"
                + "\"priority\":1,\"match\":{},\"price\":0.67,\"outputPixelsPerUnit\":24000000}]}");
        return model;
    }

    private BillingAmountCalculatorImpl calculator() {
        BillingPriceMultiplierService multiplier = mock(BillingPriceMultiplierService.class);
        when(multiplier.resolveModelMultiplier(BigDecimal.ONE)).thenReturn(BigDecimal.ONE);
        when(multiplier.getGlobalMultiplier()).thenReturn(BigDecimal.ONE);
        return new BillingAmountCalculatorImpl(new BillingRuleResolverImpl(new ObjectMapper()), multiplier);
    }
}
