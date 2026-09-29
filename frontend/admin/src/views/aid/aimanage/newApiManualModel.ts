import type { ModelCapabilityDefinition } from './modelDefinition';

/** 无账户授权时由管理员明确填写人民币价格；协议和能力保持 New API 文本接口的默认值。 */
export function newApiManualTextDefinition(modelId: string, inputPrice: number, outputPrice: number): ModelCapabilityDefinition {
  const presentation = {
    supportsTextInput: true, supportsSystemPrompt: true, supportsImageInput: false,
    supportsMultiImageInput: false, supportsAspectRatio: false, supportsSizePreset: false,
    supportsDuration: false, supportsFirstFrame: false, supportsLastFrame: false,
    defaultOutputCount: 1, maxOutputCount: 1
  };
  return {
    code: 'text', label: '文本生成', generateMode: 'text', enabled: true, defaultCapability: true,
    parameters: [{ name: 'prompt', label: '提示词', type: 'string', widget: 'textarea' }],
    rules: [], presentation,
    bindings: [{
      code: 'newapi_chat', protocol: 'openai-compatible-text', upstreamModel: modelId,
      apiSuffix: '/v1/chat/completions', defaultBinding: true, enabled: true,
      capability: {}, presentation, fixedParameters: {}, parameterMapping: {}, billingMode: 'SKU',
      billingRule: {
        mode: 'SKU', meterType: 'TOKEN', preHold: true, matchStrategy: 'FIRST_HIT', params: [],
        skus: [{ skuCode: 'newapi_tokens', skuName: '文本 Token', enabled: true, priority: 1,
          meterType: 'TOKEN', match: {}, inputPricePerMillion: inputPrice, outputPricePerMillion: outputPrice }],
        settleRule: { usageSource: 'PROVIDER_USAGE', usagePricingMode: 'BUCKETED',
          settleMode: 'REFUND_ONLY', allowRefund: true, allowExtraCharge: false, charToTokenRatio: 2 }
      }
    }]
  };
}

export function newApiManualImageDefinition(modelId: string, imagePrice: number): ModelCapabilityDefinition {
  const capability = {
    defaultSize: '1024x1024', sizeOptions: ['1024x1024'], maxReferenceImages: 0,
    sceneRules: { textToImage: { supportsSizePreset: true } }
  };
  const presentation = {
    supportsTextInput: true, supportsImageInput: false, supportsMultiImageInput: false,
    supportsAspectRatio: false, supportsSizePreset: true, supportsDuration: false,
    supportsFirstFrame: false, supportsLastFrame: false, defaultOutputCount: 1, maxOutputCount: 1,
    defaultSize: '1024x1024'
  };
  return {
    code: 'text_to_image', label: '文生图', generateMode: 'text_to_image', enabled: true,
    defaultCapability: true,
    parameters: [{ name: 'prompt', label: '提示词', type: 'string', widget: 'textarea' }],
    rules: [], presentation,
    bindings: [{
      code: 'newapi_image', protocol: 'newapi-image', upstreamModel: modelId,
      apiSuffix: '/v1/images/generations', defaultBinding: true, enabled: true,
      capability, presentation, fixedParameters: {}, parameterMapping: {}, billingMode: 'SKU',
      billingRule: {
        mode: 'SKU', chargeType: 'IMAGE', meterType: 'PER_IMAGE', preHold: true,
        matchStrategy: 'FIRST_HIT', params: [],
        skus: [{ skuCode: 'newapi_image', skuName: '图片生成', enabled: true, priority: 1,
          meterType: 'PER_IMAGE', match: {}, price: imagePrice }]
      }
    }]
  };
}

export function newApiManualModelCode(providerId: number, modelId: string): string {
  const slug = modelId.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 50) || 'model';
  let hash = 2166136261;
  for (const char of modelId) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0;
  return `newapi_${providerId}_${slug}_${hash.toString(16).padStart(8, '0')}`;
}
