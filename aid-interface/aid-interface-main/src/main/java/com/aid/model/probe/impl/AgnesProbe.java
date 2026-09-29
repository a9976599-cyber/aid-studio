package com.aid.model.probe.impl;

import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.model.probe.ProbeResult;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.media.provider.impl.AgnesVideo25RequestBuilder;

import cn.hutool.core.util.StrUtil;

/**
 * Agnes 视频结果只读查询探测。
 */
@Component
public class AgnesProbe extends AbstractReadOnlyProbe {

    private static final String PROVIDER_CODE = "agnes";
    private static final String VIDEO_PROTOCOL = "agnes-video";
    private static final Pattern TASK_MISSING_WITH_REQUEST_ID = Pattern.compile(
            "task not found \\(request id: [A-Za-z0-9_-]+\\)", Pattern.CASE_INSENSITIVE);

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        String path = ProviderEndpointUtils.normalizeTaskQueryTemplate(provider.getTaskQuerySuffix())
                .replace("%s", ProbeHttpSupport.randomProbeId());
        return AgnesVideo25RequestBuilder.appendModelName(path,
                model == null ? null : model.getRealModelCode());
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (ProbeBusinessResponseSupport.isKnownTaskMissing(response.body())
                || isTaskMissingWithRequestId(response)) {
            return success(model);
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && (root.containsKey("id") || root.containsKey("task_id") || root.containsKey("video_id"))
                && root.containsKey("status")) {
            return success(model);
        }
        return ProbeHttpSupport.unexpected(response);
    }

    private boolean isTaskMissingWithRequestId(ProbeHttpResponse response) {
        if (response.status() != 404) {
            return false;
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        JSONObject error = root == null ? null : root.getJSONObject("error");
        return error != null && Objects.equals("404", error.getString("code"))
                && TASK_MISSING_WITH_REQUEST_ID.matcher(StrUtil.trimToEmpty(error.getString("message"))).matches();
    }

    private ProbeResult success(AidAiModel model) {
        if (Objects.nonNull(model) && Objects.equals(VIDEO_PROTOCOL, model.getProtocol())) {
            return ProbeResult.ok("鉴权查询正常");
        }
        ProbeResult result = ProbeResult.ok("仅供应商鉴权正常");
        result.setDetail("未验证当前模型权限");
        return result;
    }
}
