package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.dto.ReferenceVideoInput;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 构造 DMC H3 异步视频请求并校验各能力入口的素材角色。 */
public final class DmcH3VideoRequestBuilder {
    public static final String PROTOCOL = "dmc-h3-video";
    private static final Set<String> RATIOS = Set.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final Set<String> CAPABILITIES = Set.of("text_to_video", "image_to_video",
            "last_frame_to_video", "start_end_to_video", "reference_to_video");

    private DmcH3VideoRequestBuilder() { }

    public static Map<String, Object> build(AiModelConfigVo model, MediaVideoGenerateRequest request,
                                             boolean promptPending) {
        if (model == null || request == null || !CAPABILITIES.contains(model.getCapabilityCode()))
            throw new ServiceException("模型能力无效");
        if (request.getOptions() != null && request.getOptions().keySet().stream().anyMatch(key ->
                !Set.of("resolution", "size", "lastFrameImageUrl", "endImageUrl", "end_image_url",
                        "referenceImages", "images", "referenceVideos", "referenceVideoDurations",
                        "referenceVideoSeconds", "inputVideoSeconds", "sbzVideoGenCtx",
                        "start_end", "generate_audio").contains(key)))
            throw new ServiceException("生成参数不支持");
        if (Boolean.TRUE.equals(request.getAudio())
                || Boolean.TRUE.equals(request.getOptions() == null ? null : request.getOptions().get("generate_audio")))
            throw new ServiceException("DMC 不支持显式生成音频开关");
        String prompt = StrUtil.trimToEmpty(request.getPrompt());
        if (!promptPending && prompt.isBlank()) throw new ServiceException("视频提示词不能为空");
        if (prompt.codePointCount(0, prompt.length()) > 7000) throw new ServiceException("视频提示词过长");
        Integer duration = request.getDurationSeconds();
        if (duration == null || duration < 1 || duration > 15) throw new ServiceException("视频时长范围错误");
        String resolution = option(request, "resolution", "size");
        if (resolution != null && !"768P".equals(resolution)) throw new ServiceException("分辨率不支持");

        List<Map<String, Object>> content = new ArrayList<>();
        String first = StrUtil.trimToNull(request.getImageUrl());
        String last = option(request, "lastFrameImageUrl", "endImageUrl", "end_image_url");
        if ("last_frame_to_video".equals(model.getCapabilityCode()) && last == null) {
            last = first;
            first = null;
        }
        List<String> images = urls(request, "referenceImages", "images");
        // Storyboard image direction exposes its selected source both as the
        // frame and as the reference list. It is one owned input, not a mixed
        // frame/reference request; retain rejection of any additional image.
        String frame = "last_frame_to_video".equals(model.getCapabilityCode()) ? last : first;
        if (("image_to_video".equals(model.getCapabilityCode())
                || "last_frame_to_video".equals(model.getCapabilityCode()))
                && frame != null && images.size() == 1 && frame.equals(images.get(0))) {
            images.clear();
        }
        List<String> videos = new ArrayList<>();
        if (request.getResolvedReferenceVideos() != null) for (ReferenceVideoInput input : request.getResolvedReferenceVideos())
            if (input != null && StrUtil.isNotBlank(input.getVideoUrl())) videos.add(input.getVideoUrl());
        List<String> declaredVideos = urls(request, "referenceVideos");
        if (!declaredVideos.isEmpty() && !declaredVideos.equals(videos))
            throw new ServiceException("请使用有权视频");
        List<ReferenceAudioInput> audios = request.getReferenceAudios() == null ? List.of() : request.getReferenceAudios();
        boolean hasReferences = !images.isEmpty() || !videos.isEmpty() || !audios.isEmpty()
                || request.getReferenceVideoRecordIds() != null && !request.getReferenceVideoRecordIds().isEmpty();
        String ability = model.getCapabilityCode();
        switch (ability) {
            case "text_to_video" -> {
                if (first != null || last != null || hasReferences) throw new ServiceException("文生视频不支持素材");
            }
            case "image_to_video" -> {
                if (first == null || last != null || hasReferences) throw new ServiceException("首帧素材不匹配");
                content.add(media("image_url", first, "first_frame"));
            }
            case "last_frame_to_video" -> {
                if (last == null || first != null || hasReferences) throw new ServiceException("尾帧素材不匹配");
                content.add(media("image_url", last, "last_frame"));
            }
            case "start_end_to_video" -> {
                if (first == null || last == null || hasReferences) throw new ServiceException("首尾帧素材不匹配");
                content.add(media("image_url", first, "first_frame"));
                content.add(media("image_url", last, "last_frame"));
            }
            case "reference_to_video" -> {
                if (last != null) throw new ServiceException("参考素材不能混用帧");
                if (first != null) images.add(0, first);
                List<String> uniqueImages = new ArrayList<>(new java.util.LinkedHashSet<>(images));
                images.clear();
                images.addAll(uniqueImages);
                if (images.isEmpty() && videos.isEmpty() && audios.isEmpty()
                        && !(request.getReferenceVideoRecordIds() != null
                        && !request.getReferenceVideoRecordIds().isEmpty()))
                    throw new ServiceException("请提供参考素材");
                if (images.size() > 9 || videos.size() > 3 || audios.size() > 3
                        || images.size() + videos.size() + audios.size() > 12) throw new ServiceException("参考素材数量超限");
                images.forEach(url -> content.add(media("image_url", url, "reference_image")));
                videos.forEach(url -> content.add(media("video_url", url, "reference_video")));
                for (ReferenceAudioInput audio : audios) {
                    if (audio == null || StrUtil.isBlank(audio.getSampleUrl())) throw new ServiceException("参考音频无效");
                    content.add(media("audio_url", audio.getSampleUrl(), "reference_audio"));
                }
            }
            default -> throw new ServiceException("模型能力无效");
        }
        String ratio = StrUtil.blankToDefault(request.getAspectRatio(), "16:9");
        boolean visual = first != null || last != null || !images.isEmpty() || !videos.isEmpty();
        if (!RATIOS.contains(ratio) && !(visual && "adaptive".equals(ratio)))
            throw new ServiceException("视频比例不支持");
        // Convert private storyboard markers using the exact dispatched media order.
        // Preflight and submission must build the same immutable upstream payload.
        String dispatchedPrompt = ReferencePromptSanitizer.sanitizeForAgnes25(prompt,
                "reference_to_video".equals(ability) ? images.size() : 0,
                videos.size(), audios.size());
        content.add(0, Map.of("type", "text", "text", dispatchedPrompt));
        Map<String, Object> body = new LinkedHashMap<>();
        if (StrUtil.isBlank(model.getRealModelCode())) throw new ServiceException("真实模型未配置");
        body.put("model", model.getRealModelCode());
        body.put("content", content);
        body.put("resolution", "768P");
        body.put("duration", duration);
        body.put("ratio", ratio);
        return body;
    }

    private static Map<String, Object> media(String type, String rawUrl, String role) {
        String url = StrUtil.trimToEmpty(rawUrl);
        if (url.startsWith("/") && !url.startsWith("//"))
            return Map.of("type", type, type, Map.of("url", url), "role", role);
        try {
            URI parsed = URI.create(url);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getFragment() != null)
                throw new IllegalArgumentException("invalid URL");
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("素材地址无效");
        }
        return Map.of("type", type, type, Map.of("url", url), "role", role);
    }

    private static List<String> urls(MediaVideoGenerateRequest request, String... names) {
        List<String> values = new ArrayList<>();
        if (request.getOptions() == null) return values;
        for (String name : names) {
            Object raw = request.getOptions().get(name);
            if (raw instanceof List<?> list) for (Object value : list) {
                if (value instanceof String text) values.add(text);
                else if (value instanceof Map<?, ?> map && map.get("url") instanceof String text) values.add(text);
            }
        }
        return values;
    }

    private static String option(MediaVideoGenerateRequest request, String... names) {
        if (request.getOptions() == null) return null;
        for (String name : names) {
            Object value = request.getOptions().get(name);
            if (value != null) return String.valueOf(value).trim();
        }
        return null;
    }
}
