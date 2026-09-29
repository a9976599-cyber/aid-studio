package com.aid.newapi;

import com.aid.media.provider.impl.OpenAiImageProviderClient;
import org.springframework.stereotype.Component;

/** New API 的 Images 端点沿用 OpenAI 兼容请求与响应，协议标识独立于模型原厂。 */
@Component
public class NewApiImageProviderClient extends OpenAiImageProviderClient {
    @Override
    public String protocol() {
        return "newapi-image";
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return false;
    }
}
