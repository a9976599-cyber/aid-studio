package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelProtocolBinding;
import java.util.List;

/** 汇总已启用能力的媒体输入支持情况。 */
final class ModelSupportOverview {
    private ModelSupportOverview() { }

    static void apply(AidAiModel model, List<ModelCapabilityDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) return;
        Boolean image = null;
        Boolean multiImage = null;
        Boolean firstFrame = null;
        Boolean lastFrame = null;
        for (ModelCapabilityDefinition definition : definitions) {
            if (definition == null || !Boolean.TRUE.equals(definition.getEnabled())
                    || definition.getBindings() == null) continue;
            for (ModelProtocolBinding binding : definition.getBindings()) {
                if (binding == null || !Boolean.TRUE.equals(binding.getEnabled())) continue;
                AidAiModel view = new AidAiModel();
                ModelInvocationResolver.applyResolvedPresentation(view, definition, binding);
                image = union(image, view.getSupportsImageInput());
                multiImage = union(multiImage, view.getSupportsMultiImageInput());
                firstFrame = union(firstFrame, view.getSupportsFirstFrame());
                lastFrame = union(lastFrame, view.getSupportsLastFrame());
            }
        }
        if (image != null) model.setSupportsImageInput(image);
        if (multiImage != null) model.setSupportsMultiImageInput(multiImage);
        if (firstFrame != null) model.setSupportsFirstFrame(firstFrame);
        if (lastFrame != null) model.setSupportsLastFrame(lastFrame);
    }

    private static Boolean union(Boolean accumulated, Boolean next) {
        if (next == null) return accumulated;
        return Boolean.TRUE.equals(accumulated) || next;
    }
}
