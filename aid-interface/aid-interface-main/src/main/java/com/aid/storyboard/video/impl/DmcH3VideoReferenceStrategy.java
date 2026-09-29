package com.aid.storyboard.video.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.provider.DmcH3VideoRequestBuilder;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 按 DMC H3 的关键帧和参考素材角色装配分镜视频输入。 */
@Component
public class DmcH3VideoReferenceStrategy extends AbstractVideoReferenceStrategy {
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    @Override
    public boolean supportsModelConfig(AiModelConfigVo model) {
        return model != null && DmcH3VideoRequestBuilder.PROTOCOL.equalsIgnoreCase(
                StrUtil.trim(model.getProtocol()));
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext context) {
        AiModelConfigVo model = context.getModelConfig();
        String capability = model == null ? null : model.getCapabilityCode();
        // Count the distinct URLs that will actually be dispatched, including the base image.
        List<ResolvedReference> references = takeRefs(context.getReferences(), -1);
        String base = StrUtil.trimToNull(context.getBaseImageUrl());
        String last = StrUtil.trimToNull(context.getLastFrameImageUrl());
        return switch (capability == null ? "" : capability) {
            case "text_to_video" -> {
                if (base != null || last != null || !references.isEmpty())
                    throw new ServiceException("文生视频不支持素材");
                yield VideoReferencePlan.of(prompt(context, Map.of()), List.of(), null);
            }
            case "image_to_video", "last_frame_to_video" -> {
                Map<String, Integer> images = imageIndexes(base, references);
                if (last != null || images.size() != 1)
                    throw new ServiceException("单帧图片不匹配");
                yield VideoReferencePlan.of(prompt(context, referenceIndexes(references, images)),
                        List.of(), images.keySet().iterator().next());
            }
            case "start_end_to_video" -> {
                if (base == null || last == null || !references.isEmpty())
                    throw new ServiceException("首尾帧图片不匹配");
                yield VideoReferencePlan.of(prompt(context, Map.of()), List.of(), base);
            }
            case "reference_to_video" -> {
                if (last != null) throw new ServiceException("参考素材不能混用帧");
                Map<String, Integer> images = imageIndexes(base, references);
                if (context.getMaxReferenceImages() >= 0
                        && images.size() > context.getMaxReferenceImages())
                    throw new ServiceException("参考图数量超限");
                yield VideoReferencePlan.of(prompt(context, referenceIndexes(references, images)),
                        new ArrayList<>(images.keySet()), null);
            }
            default -> throw new ServiceException("模型场景配置无效");
        };
    }

    private Map<String, Integer> imageIndexes(String base, List<ResolvedReference> references) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        if (base != null) indexes.put(base, 1);
        for (ResolvedReference reference : references)
            indexes.computeIfAbsent(reference.getUrl(), unused -> indexes.size() + 1);
        return indexes;
    }

    private Map<Integer, Integer> referenceIndexes(List<ResolvedReference> references,
                                                    Map<String, Integer> images) {
        Map<Integer, Integer> indexes = new LinkedHashMap<>();
        for (ResolvedReference reference : references)
            indexes.put(reference.getOriginalN(), images.get(reference.getUrl()));
        return indexes;
    }

    private String prompt(VideoReferenceContext context, Map<Integer, Integer> indexes) {
        Matcher matcher = IMAGE_REFERENCE.matcher(StrUtil.nullToEmpty(context.getVideoPrompt()));
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            int original;
            try {
                original = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new ServiceException("参考图编号无效");
            }
            Integer compact = indexes.get(original);
            if (compact == null) throw new ServiceException("参考图编号无效");
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                    "@图片" + compact + "[" + matcher.group(2) + "]"));
        }
        matcher.appendTail(rewritten);
        return composePrompt(rewritten.toString(), null, context.getUserInputText());
    }
}
