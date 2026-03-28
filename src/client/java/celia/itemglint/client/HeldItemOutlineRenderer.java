package celia.itemglint.client;

import celia.itemglint.mixin.client.FeatureRenderDispatcherAccessor;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class HeldItemOutlineRenderer {
    private static final long RULE_SWITCH_DELAY_MILLIS = 200L;
    private static final long ANIMATION_TIME_WRAP_TICKS = 240000L;
    private static final float BLOOM_BLUR_PASS_RADIUS = 2.5F;
    private static final float BLOOM_BLUR_KERNEL_RADIUS = 6.9230769F;
    private static final int MIN_BLOOM_TARGET_SIZE = 64;
    private static final int OUTLINE_INFO_VEC4_COUNT = 14;
    private static final int BLUR_INFO_VEC4_COUNT = 2;
    private static final int OUTLINE_UNIFORM_CAPACITY = 4;
    private static final int BLUR_UNIFORM_CAPACITY = 32;
    private static final float[][] HAND_RENDER_BOUNDS_SAMPLES = createHandRenderBoundsSamples();
    private static final int SCISSOR_BASE_PADDING = 36;
    private static final int SCISSOR_BLOOM_PADDING = 10;
    private static final float SMALL_BLOOM_FAST_PATH_RADIUS = 1.0F;

    private static final CaptureState MAIN_HAND_STATE = new CaptureState(InteractionHand.MAIN_HAND);
    private static final CaptureState OFF_HAND_STATE = new CaptureState(InteractionHand.OFF_HAND);
    private static final UniformBufferPool OUTLINE_INFO_POOL = new UniformBufferPool(
            "itemglint_outline_uniforms",
            OUTLINE_INFO_VEC4_COUNT * 16,
            OUTLINE_UNIFORM_CAPACITY
    );
    private static final UniformBufferPool BLUR_INFO_POOL = new UniformBufferPool(
            "itemglint_blur_uniforms",
            BLUR_INFO_VEC4_COUNT * 16,
            BLUR_UNIFORM_CAPACITY
    );

    @Nullable
    private static InteractionHand activeSubmitHand;
    @Nullable
    private static Matrix4f activeHandProjectionMatrix;
    private static int duplicationSuppressDepth;
    private static TextureTarget bloomBlurTargetA;
    private static TextureTarget bloomBlurTargetB;
    private static TextureTarget bloomBlurNearTarget;

    private HeldItemOutlineRenderer() {
    }

    public static boolean shouldRenderOutlinePass(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        return HeldItemOutlinePipelines.isLoaded()
                && ShaderFeatureManager.isEnabled(ShaderFeature.HELD_ITEM_OUTLINE)
                && minecraft.level != null
                && player != null
                && shouldRenderOutline(minecraft)
                && !getRenderableHands(player).isEmpty();
    }

    public static List<HandEffectTarget> getRenderableHands(LocalPlayer player) {
        if (player == null) {
            return List.of();
        }

        List<HandEffectTarget> targets = new ArrayList<>(2);
        addHandTarget(targets, InteractionHand.MAIN_HAND, HeldItemOutlineSettings.isMainHandEnabled(), player.getMainHandItem());
        addHandTarget(targets, InteractionHand.OFF_HAND, HeldItemOutlineSettings.isOffHandEnabled(), player.getOffhandItem());
        return targets;
    }

    public static void beginFrame() {
        activeSubmitHand = null;
        activeHandProjectionMatrix = null;
        duplicationSuppressDepth = 0;
        OUTLINE_INFO_POOL.beginFrame();
        BLUR_INFO_POOL.beginFrame();
        resetFrameState(MAIN_HAND_STATE);
        resetFrameState(OFF_HAND_STATE);
    }

    public static boolean hasPendingComposites() {
        return hasPendingComposite(MAIN_HAND_STATE) || hasPendingComposite(OFF_HAND_STATE);
    }

    public static SubmitNodeCollector wrapSubmitCollector(Minecraft minecraft, SubmitNodeCollector submitNodeCollector, LocalPlayer player) {
        if (!shouldRenderOutlinePass(minecraft)) {
            return submitNodeCollector;
        }

        prepareCaptureStates(minecraft, player, false);
        if (!hasCaptureTarget(MAIN_HAND_STATE) && !hasCaptureTarget(OFF_HAND_STATE)) {
            return submitNodeCollector;
        }

        return new DuplicatingSubmitNodeCollector(minecraft, submitNodeCollector);
    }

    public static SubmitNodeStorage wrapSubmitStorage(Minecraft minecraft, SubmitNodeStorage submitNodeStorage, LocalPlayer player) {
        if (!shouldRenderOutlinePass(minecraft)) {
            return submitNodeStorage;
        }

        prepareCaptureStates(minecraft, player, true);
        if (!hasCaptureTarget(MAIN_HAND_STATE) && !hasCaptureTarget(OFF_HAND_STATE)) {
            return submitNodeStorage;
        }

        if (submitNodeStorage instanceof DuplicatingSubmitNodeStorage) {
            return submitNodeStorage;
        }

        return new DuplicatingSubmitNodeStorage(minecraft, submitNodeStorage);
    }

    public static void beginHandSubmit(InteractionHand hand, ItemStack stack) {
        activeSubmitHand = stack.isEmpty() ? null : hand;
        if (activeSubmitHand != null) {
            captureActiveHandRenderMatrices();
        }
    }

    public static void endHandSubmit() {
        activeSubmitHand = null;
    }

    public static void beginItemInHandRender(Matrix4f projectionMatrix) {
        activeHandProjectionMatrix = projectionMatrix == null ? null : new Matrix4f(projectionMatrix);
    }

    public static void endItemInHandRender() {
        activeHandProjectionMatrix = null;
    }

    public static void beginDuplicationSuppress() {
        duplicationSuppressDepth++;
    }

    public static void endDuplicationSuppress() {
        if (duplicationSuppressDepth > 0) {
            duplicationSuppressDepth--;
        }
    }

    public static void renderPendingComposites(Minecraft minecraft, RenderTarget mainTarget) {
        if (canBatchPendingComposites(MAIN_HAND_STATE, OFF_HAND_STATE)) {
            renderPendingCompositeBatch(minecraft, mainTarget, MAIN_HAND_STATE, OFF_HAND_STATE);
            return;
        }

        renderPendingComposite(minecraft, mainTarget, MAIN_HAND_STATE);
        renderPendingComposite(minecraft, mainTarget, OFF_HAND_STATE);
    }

    private static void renderPendingComposite(Minecraft minecraft, RenderTarget mainTarget, CaptureState state) {
        if (!hasPendingComposite(state) || state.outlineMaskTarget == null) {
            resetFrameState(state);
            return;
        }

        clearTarget(state.outlineMaskTarget);
        renderCapturedNodes(state, state.outlineMaskTarget);

        ResolvedRenderState renderState = state.capturedRenderState;
        ScissorRect scissorRect = state.scissorRect;
        float time = resolveAnimationTime(minecraft);
        try {
            GpuBufferSlice outlineInfo = createOutlineInfoBuffer(mainTarget.width, mainTarget.height,
                    renderState.profile(), renderState.sampledColors(), time);
            drawOutline(mainTarget, state.outlineMaskTarget, outlineInfo, scissorRect);
            if (renderState.profile().bloom()) {
                compositeBloom(mainTarget, state.outlineMaskTarget, renderState.profile(), outlineInfo, scissorRect);
            }
        } finally {
            cleanupCaptureState(state);
        }
    }

    private static void renderPendingCompositeBatch(Minecraft minecraft, RenderTarget mainTarget, CaptureState primary, CaptureState secondary) {
        if (!hasPendingComposite(primary) || !hasPendingComposite(secondary) || primary.outlineMaskTarget == null) {
            renderPendingComposite(minecraft, mainTarget, primary);
            renderPendingComposite(minecraft, mainTarget, secondary);
            return;
        }

        clearTarget(primary.outlineMaskTarget);
        renderCapturedNodes(primary, primary.outlineMaskTarget);
        renderCapturedNodes(secondary, primary.outlineMaskTarget);

        ResolvedRenderState renderState = primary.capturedRenderState;
        ScissorRect scissorRect = ScissorRect.union(primary.scissorRect, secondary.scissorRect);
        float time = resolveAnimationTime(minecraft);
        try {
            GpuBufferSlice outlineInfo = createOutlineInfoBuffer(mainTarget.width, mainTarget.height,
                    renderState.profile(), renderState.sampledColors(), time);
            drawOutline(mainTarget, primary.outlineMaskTarget, outlineInfo, scissorRect);
            if (renderState.profile().bloom()) {
                compositeBloom(mainTarget, primary.outlineMaskTarget, renderState.profile(), outlineInfo, scissorRect);
            }
        } finally {
            cleanupCaptureState(primary);
            cleanupCaptureState(secondary);
        }
    }

    private static void drawOutline(RenderTarget mainTarget, TextureTarget maskTarget, GpuBufferSlice outlineInfo,
                                    @Nullable ScissorRect scissorRect) {
        GpuSampler nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "itemglint_outline", mainTarget.getColorTextureView(), java.util.OptionalInt.empty())) {
            applyScissor(pass, scissorRect);
            pass.setPipeline(HeldItemOutlinePipelines.outline());
            pass.setUniform("OutlineInfo", outlineInfo);
            pass.bindTexture("DiffuseSampler", maskTarget.getColorTextureView(), nearestSampler);
            pass.bindTexture("DepthSampler", maskTarget.getDepthTextureView(), nearestSampler);
            pass.draw(0, 3);
        }
    }

    private static void compositeBloom(RenderTarget mainTarget, TextureTarget outlineMaskTarget,
                                       HeldItemOutlineEffectProfile profile,
                                       GpuBufferSlice outlineInfo,
                                       @Nullable ScissorRect scissorRect) {
        ensureBloomTargets(mainTarget, profile);

        float nearBlurRadius = getNearBlurRadius(profile);
        float farBlurRadius = getFarBlurRadius(profile);
        int farBlurPasses = getFarBlurPasses(profile, farBlurRadius);
        float farPassRadius = farBlurRadius / farBlurPasses;

        ScissorRect bloomScissor = scaleScissorRect(scissorRect, mainTarget, bloomBlurTargetA);
        GpuTextureView nearTexture = applyBlurChain(outlineMaskTarget.getColorTextureView(), bloomBlurTargetA,
                bloomBlurNearTarget, nearBlurRadius, 1, bloomScissor);
        GpuTextureView farTexture = shouldUseNearOnlyBloom(profile, farBlurPasses)
                ? nearTexture
                : applyBlurChain(outlineMaskTarget.getColorTextureView(), bloomBlurTargetA,
                bloomBlurTargetB, farPassRadius, farBlurPasses, bloomScissor);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "itemglint_bloom", mainTarget.getColorTextureView(), java.util.OptionalInt.empty())) {
            GpuSampler linearSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            applyScissor(pass, scissorRect);
            pass.setPipeline(HeldItemOutlinePipelines.bloom());
            pass.setUniform("OutlineInfo", outlineInfo);
            pass.bindTexture("DiffuseSampler", outlineMaskTarget.getColorTextureView(), linearSampler);
            pass.bindTexture("NearBlurSampler", nearTexture, linearSampler);
            pass.bindTexture("FarBlurSampler", farTexture, linearSampler);
            pass.draw(0, 3);
        }
    }

    private static GpuTextureView applyBlurChain(GpuTextureView sourceTexture, TextureTarget scratchTarget,
                                                 TextureTarget destinationTarget, float blurRadius, int passes,
                                                 @Nullable ScissorRect scissorRect) {
        if (scratchTarget == null || destinationTarget == null || passes <= 0) {
            return sourceTexture;
        }

        GpuTextureView currentTexture = sourceTexture;
        for (int i = 0; i < passes; i++) {
            GpuBufferSlice horizontalInfo = createBlurInfoBuffer(scratchTarget.width, scratchTarget.height, 1.0F, 0.0F, blurRadius);
            try (RenderPass horizontalPass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "itemglint_bloom_blur_horizontal", scratchTarget.getColorTextureView(), java.util.OptionalInt.empty())) {
                GpuSampler linearSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                applyScissor(horizontalPass, scissorRect);
                horizontalPass.setPipeline(HeldItemOutlinePipelines.bloomBlur());
                horizontalPass.setUniform("BlurInfo", horizontalInfo);
                horizontalPass.bindTexture("DiffuseSampler", currentTexture, linearSampler);
                horizontalPass.draw(0, 3);
            }

            GpuBufferSlice verticalInfo = createBlurInfoBuffer(destinationTarget.width, destinationTarget.height, 0.0F, 1.0F, blurRadius);
            try (RenderPass verticalPass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "itemglint_bloom_blur_vertical", destinationTarget.getColorTextureView(), java.util.OptionalInt.empty())) {
                GpuSampler linearSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                applyScissor(verticalPass, scissorRect);
                verticalPass.setPipeline(HeldItemOutlinePipelines.bloomBlur());
                verticalPass.setUniform("BlurInfo", verticalInfo);
                verticalPass.bindTexture("DiffuseSampler", scratchTarget.getColorTextureView(), linearSampler);
                verticalPass.draw(0, 3);
            }

            currentTexture = destinationTarget.getColorTextureView();
        }
        return currentTexture;
    }

    private static void renderCapturedNodes(CaptureState state, TextureTarget maskTarget) {
        if (state.captureFeatureDispatcher == null || state.captureRenderBuffers == null) {
            return;
        }

        GpuTextureView oldColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView oldDepth = RenderSystem.outputDepthTextureOverride;
        boolean restoreProjection = state.capturedProjectionMatrix != null && state.capturedProjectionType != null;
        boolean restoreModelView = state.capturedModelViewMatrix != null;
        RenderSystem.outputColorTextureOverride = maskTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = maskTarget.getDepthTextureView();
        if (restoreProjection) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(state.capturedProjectionMatrix, state.capturedProjectionType);
        }
        if (restoreModelView) {
            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
        }
        try {
            state.captureFeatureDispatcher.renderAllFeatures();
            state.captureRenderBuffers.bufferSource().endBatch();
            state.captureRenderBuffers.outlineBufferSource().endOutlineBatch();
            state.captureRenderBuffers.crumblingBufferSource().endBatch();
        } finally {
            if (restoreModelView) {
                RenderSystem.getModelViewStack().popMatrix();
            }
            if (restoreProjection) {
                RenderSystem.restoreProjectionMatrix();
            }
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }
    }

    private static FeatureRenderDispatcher ensureCaptureDispatcher(Minecraft minecraft, CaptureState state) {
        if (state.captureFeatureDispatcher == null || state.captureRenderBuffers == null) {
            state.captureRenderBuffers = new RenderBuffers(1);
            FeatureRenderDispatcher dispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
            FeatureRenderDispatcherAccessor accessor = (FeatureRenderDispatcherAccessor) dispatcher;
            state.captureFeatureDispatcher = new FeatureRenderDispatcher(
                    new SubmitNodeStorage(),
                    minecraft.getBlockRenderer(),
                    state.captureRenderBuffers.bufferSource(),
                    accessor.itemglint$getAtlasManager(),
                    state.captureRenderBuffers.outlineBufferSource(),
                    state.captureRenderBuffers.crumblingBufferSource(),
                    minecraft.font
            );
        }
        return state.captureFeatureDispatcher;
    }

    @Nullable
    private static OrderedSubmitNodeCollector captureCollectorForCurrentHand(Minecraft minecraft, int order) {
        if (activeSubmitHand == null || duplicationSuppressDepth > 0) {
            return null;
        }

        CaptureState state = stateFor(activeSubmitHand);
        if (!hasCaptureTarget(state)) {
            return null;
        }

        FeatureRenderDispatcher dispatcher = ensureCaptureDispatcher(minecraft, state);
        return dispatcher.getSubmitNodeStorage().order(order);
    }

    private static void prepareCaptureStates(Minecraft minecraft, LocalPlayer player, boolean preserveExistingCaptures) {
        if (!preserveExistingCaptures) {
            resetFrameState(MAIN_HAND_STATE);
            resetFrameState(OFF_HAND_STATE);
        }

        for (HandEffectTarget target : getRenderableHands(player)) {
            CaptureState state = stateFor(target.hand());
            state.capturedRenderState = new ResolvedRenderState(target.profile(), target.sampledColors());
            ensureOutlineMaskTarget(minecraft.getMainRenderTarget(), state);
            FeatureRenderDispatcher dispatcher = ensureCaptureDispatcher(minecraft, state);
            if (!preserveExistingCaptures || !state.capturePreparedThisFrame) {
                dispatcher.getSubmitNodeStorage().clear();
                state.capturedThisFrame = false;
                state.capturePreparedThisFrame = true;
            }
        }
    }

    private static boolean shouldRenderOutline(Minecraft minecraft) {
        if (!minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (minecraft.options.hideGui) {
            return false;
        }
        return minecraft.gameMode != null && !minecraft.player.isSpectator() && !minecraft.player.isSleeping();
    }

    private static void addHandTarget(List<HandEffectTarget> targets, InteractionHand hand, boolean handEnabled, ItemStack stack) {
        HandEffectTarget target = resolveHandTarget(hand, handEnabled, stack);
        if (target != null) {
            targets.add(target);
        }
    }

    @Nullable
    private static HandEffectTarget resolveHandTarget(InteractionHand hand, boolean handEnabled, ItemStack stack) {
        CaptureState state = stateFor(hand);
        ItemStack observedStack = handEnabled ? stack : ItemStack.EMPTY;
        ResolvedRenderState nextState = resolveCachedRenderState(state, handEnabled, observedStack, stack);
        long now = System.currentTimeMillis();
        if (state.transitionEndMillis != 0L && (!HeldItemOutlineSettings.isRuleSwitchDelayEnabled() || now >= state.transitionEndMillis)) {
            completePendingTransition(state);
        }

        if (state.lastObservedHandEnabled != handEnabled || !ItemStack.isSameItemSameComponents(state.lastObservedStack, observedStack)) {
            state.lastObservedHandEnabled = handEnabled;
            state.lastObservedStack = observedStack.copy();
            beginRenderTransition(state, nextState, now);
        }

        if (state.transitionEndMillis != 0L) {
            return toHandEffectTarget(hand, state.lastEffectiveState);
        }

        state.lastEffectiveState = nextState;
        return toHandEffectTarget(hand, state.lastEffectiveState);
    }

    @Nullable
    private static ResolvedRenderState createResolvedRenderState(ItemStack stack, @Nullable HeldItemOutlineEffectProfile profile) {
        if (profile == null) {
            return null;
        }
        return new ResolvedRenderState(profile, resolveSampledColors(stack, profile));
    }

    @Nullable
    private static ResolvedRenderState resolveCachedRenderState(CaptureState state, boolean handEnabled, ItemStack observedStack, ItemStack liveStack) {
        HeldItemOutlineEffectProfile baseProfile = HeldItemOutlineEffectProfile.captureCurrent();
        long ruleRevision = HeldItemRuleManager.getRevision();
        if (state.cachedBaseProfile != null
                && state.cachedRuleRevision == ruleRevision
                && state.cachedObservedHandEnabled == handEnabled
                && state.cachedBaseProfile.equals(baseProfile)
                && ItemStack.isSameItemSameComponents(state.cachedObservedResolvedStack, observedStack)) {
            return state.cachedResolvedState;
        }

        state.cachedObservedHandEnabled = handEnabled;
        state.cachedObservedResolvedStack = observedStack.copy();
        state.cachedBaseProfile = baseProfile;
        state.cachedRuleRevision = ruleRevision;
        HeldItemRuleManager.ResolvedMatch resolvedMatch = handEnabled && !liveStack.isEmpty()
                ? HeldItemRuleManager.resolveMatch(liveStack, baseProfile)
                : HeldItemRuleManager.ResolvedMatch.NO_MATCH;
        state.cachedResolvedState = createResolvedRenderState(liveStack, resolvedMatch.profile());
        return state.cachedResolvedState;
    }

    @Nullable
    private static HandEffectTarget toHandEffectTarget(InteractionHand hand, @Nullable ResolvedRenderState state) {
        return state == null ? null : new HandEffectTarget(hand, state.profile(), state.sampledColors());
    }

    private static void completePendingTransition(CaptureState state) {
        state.lastEffectiveState = state.pendingState;
        state.pendingState = null;
        state.transitionEndMillis = 0L;
    }

    private static void beginRenderTransition(CaptureState state, @Nullable ResolvedRenderState nextState, long now) {
        ResolvedRenderState currentState = state.lastEffectiveState;
        boolean currentlyRendering = currentState != null;
        boolean shouldRenderNext = nextState != null;
        boolean sameEffect = sameRenderedEffect(currentState, nextState);
        if (currentlyRendering == shouldRenderNext && sameEffect) {
            state.lastEffectiveState = nextState;
            state.pendingState = null;
            state.transitionEndMillis = 0L;
            return;
        }

        if (!HeldItemOutlineSettings.isRuleSwitchDelayEnabled()) {
            state.lastEffectiveState = nextState;
            state.pendingState = null;
            state.transitionEndMillis = 0L;
            return;
        }

        state.pendingState = nextState;
        state.transitionEndMillis = now + RULE_SWITCH_DELAY_MILLIS;
    }

    private static boolean sameRenderedEffect(@Nullable ResolvedRenderState currentState, @Nullable ResolvedRenderState nextState) {
        if (currentState == nextState) {
            return true;
        }
        if (currentState == null || nextState == null) {
            return false;
        }
        return currentState.profile().equals(nextState.profile())
                && sameSampledColors(currentState.sampledColors(), nextState.sampledColors());
    }

    private static boolean sameSampledColors(HeldItemOutlineColorSampler.SampledColors currentColors,
                                             HeldItemOutlineColorSampler.SampledColors nextColors) {
        if (currentColors == nextColors) {
            return true;
        }
        if (currentColors == null || nextColors == null || currentColors.size() != nextColors.size()) {
            return false;
        }
        for (int i = 0; i < currentColors.size(); i++) {
            float[] current = currentColors.color(i);
            float[] next = nextColors.color(i);
            if (current.length != next.length) {
                return false;
            }
            for (int channel = 0; channel < current.length; channel++) {
                if (Float.compare(current[channel], next[channel]) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static HeldItemOutlineColorSampler.SampledColors resolveSampledColors(ItemStack stack, HeldItemOutlineEffectProfile profile) {
        return profile.colorMode() == HeldItemOutlineSettings.ColorMode.AUTO_SAMPLE_SCROLL
                ? HeldItemOutlineColorSampler.sample(stack, profile)
                : HeldItemOutlineColorSampler.SampledColors.EMPTY;
    }

    private static CaptureState stateFor(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? MAIN_HAND_STATE : OFF_HAND_STATE;
    }

    private static boolean hasPendingComposite(CaptureState state) {
        return state.capturedThisFrame && state.capturedRenderState != null;
    }

    private static boolean hasCaptureTarget(CaptureState state) {
        return state.capturedRenderState != null;
    }

    private static boolean canBatchPendingComposites(CaptureState first, CaptureState second) {
        return hasPendingComposite(first)
                && hasPendingComposite(second)
                && first.outlineMaskTarget != null
                && second.outlineMaskTarget != null
                && sameRenderedEffect(first.capturedRenderState, second.capturedRenderState);
    }

    private static void cleanupCaptureState(CaptureState state) {
        if (state.captureFeatureDispatcher != null) {
            state.captureFeatureDispatcher.getSubmitNodeStorage().endFrame();
            state.captureFeatureDispatcher.endFrame();
            state.captureFeatureDispatcher.getSubmitNodeStorage().clear();
        }
        resetFrameState(state);
    }

    private static void resetFrameState(CaptureState state) {
        state.capturedThisFrame = false;
        state.capturedRenderState = null;
        state.capturePreparedThisFrame = false;
        state.capturedModelViewMatrix = null;
        state.capturedProjectionMatrix = null;
        state.capturedProjectionType = null;
        state.scissorRect = null;
    }

    private static void captureActiveHandRenderMatrices() {
        if (activeSubmitHand == null) {
            return;
        }

        CaptureState state = stateFor(activeSubmitHand);
        state.capturedModelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        state.capturedProjectionMatrix = RenderSystem.getProjectionMatrixBuffer();
        state.capturedProjectionType = RenderSystem.getProjectionType();
        state.scissorRect = resolveHandScissorRect(state);
    }

    private static void ensureOutlineMaskTarget(RenderTarget mainTarget, CaptureState state) {
        if (state.outlineMaskTarget == null) {
            state.outlineMaskTarget = new TextureTarget("itemglint_outline_mask_" + state.hand.name().toLowerCase(), mainTarget.width, mainTarget.height, true);
        } else if (state.outlineMaskTarget.width != mainTarget.width || state.outlineMaskTarget.height != mainTarget.height) {
            state.outlineMaskTarget.resize(mainTarget.width, mainTarget.height);
        }
    }

    private static void ensureBloomTargets(RenderTarget mainTarget, HeldItemOutlineEffectProfile profile) {
        int bloomWidth = getBloomTargetDimension(mainTarget.width, profile);
        int bloomHeight = getBloomTargetDimension(mainTarget.height, profile);

        if (bloomBlurTargetA == null) {
            bloomBlurTargetA = new TextureTarget("itemglint_bloom_a", bloomWidth, bloomHeight, false);
        } else if (bloomBlurTargetA.width != bloomWidth || bloomBlurTargetA.height != bloomHeight) {
            bloomBlurTargetA.resize(bloomWidth, bloomHeight);
        }

        if (bloomBlurTargetB == null) {
            bloomBlurTargetB = new TextureTarget("itemglint_bloom_b", bloomWidth, bloomHeight, false);
        } else if (bloomBlurTargetB.width != bloomWidth || bloomBlurTargetB.height != bloomHeight) {
            bloomBlurTargetB.resize(bloomWidth, bloomHeight);
        }

        if (bloomBlurNearTarget == null) {
            bloomBlurNearTarget = new TextureTarget("itemglint_bloom_near", bloomWidth, bloomHeight, false);
        } else if (bloomBlurNearTarget.width != bloomWidth || bloomBlurNearTarget.height != bloomHeight) {
            bloomBlurNearTarget.resize(bloomWidth, bloomHeight);
        }
    }

    private static int getBloomTargetDimension(int fullSize, HeldItemOutlineEffectProfile profile) {
        int downsampleFactor = profile.bloomResolution().downsampleFactor();
        return Math.max(MIN_BLOOM_TARGET_SIZE, Math.max(1, fullSize / Math.max(1, downsampleFactor)));
    }

    private static void clearTarget(RenderTarget target) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (target.useDepth) {
            encoder.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0D);
        } else {
            encoder.clearColorTexture(target.getColorTexture(), 0);
        }
    }

    private static GpuBufferSlice createOutlineInfoBuffer(int screenWidth, int screenHeight,
                                                          HeldItemOutlineEffectProfile profile,
                                                          HeldItemOutlineColorSampler.SampledColors sampledColors,
                                                          float time) {
        return OUTLINE_INFO_POOL.write(buffer -> {
            putVec4(buffer, profile.red(), profile.green(), profile.blue(), 1.0F);
            putVec4(buffer, profile.secondaryRed(), profile.secondaryGreen(), profile.secondaryBlue(), 1.0F);
            putVec4(buffer, screenWidth, screenHeight, profile.width(), profile.softness());
            putVec4(buffer, profile.alphaThreshold(), profile.opacity(), profile.depthWeight(), profile.glowStrength());
            putVec4(buffer, profile.colorMode().shaderValue(), profile.colorScrollSpeed(), time, resolvePaletteSize(sampledColors));
            putVec4(buffer, profile.bloomStrength(), profile.bloomRadius(), 0.0F, 0.0F);
            for (float[] color : resolvePalette(sampledColors, profile)) {
                putVec4(buffer, color[0], color[1], color[2], 1.0F);
            }
        });
    }

    private static GpuBufferSlice createBlurInfoBuffer(int screenWidth, int screenHeight, float dirX, float dirY, float blurRadius) {
        return BLUR_INFO_POOL.write(buffer -> {
            putVec4(buffer, screenWidth, screenHeight, dirX, dirY);
            putVec4(buffer, blurRadius, 0.0F, 0.0F, 0.0F);
        });
    }

    private static float resolveAnimationTime(Minecraft minecraft) {
        if (minecraft.level == null) {
            return 0.0F;
        }

        long wrappedGameTime = Math.floorMod(minecraft.level.getGameTime(), ANIMATION_TIME_WRAP_TICKS);
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return (wrappedGameTime + partialTick) * 0.05F;
    }

    private static void putVec4(ByteBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(z);
        buffer.putFloat(w);
    }

    private static float resolvePaletteSize(HeldItemOutlineColorSampler.SampledColors sampledColors) {
        return Math.max(2, sampledColors.size());
    }

    private static float getNearBlurRadius(HeldItemOutlineEffectProfile profile) {
        return Math.max(1.0F, 1.2F + profile.width() * 0.35F);
    }

    private static float getFarBlurRadius(HeldItemOutlineEffectProfile profile) {
        return Math.max(1.0F, profile.bloomRadius() * 3.25F);
    }

    private static int getFarBlurPasses(HeldItemOutlineEffectProfile profile, float farBlurRadius) {
        return Mth.clamp((int) Math.ceil(farBlurRadius / BLOOM_BLUR_PASS_RADIUS),
                HeldItemOutlineSettings.MIN_BLOOM_MAX_PASSES,
                profile.bloomMaxPasses());
    }

    private static boolean shouldUseNearOnlyBloom(HeldItemOutlineEffectProfile profile, int farBlurPasses) {
        return profile.bloomRadius() <= SMALL_BLOOM_FAST_PATH_RADIUS && farBlurPasses <= 2;
    }

    private static int getBloomPadding(HeldItemOutlineEffectProfile profile) {
        float effectiveBlurRadius = shouldUseNearOnlyBloom(profile, getFarBlurPasses(profile, getFarBlurRadius(profile)))
                ? getNearBlurRadius(profile)
                : getFarBlurRadius(profile);
        return Mth.ceil(BLOOM_BLUR_KERNEL_RADIUS * effectiveBlurRadius * profile.bloomResolution().downsampleFactor())
                + SCISSOR_BLOOM_PADDING;
    }

    @Nullable
    private static ScissorRect resolveHandScissorRect(CaptureState state) {
        if (activeHandProjectionMatrix == null || state.capturedModelViewMatrix == null) {
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return null;
        }

        Matrix4f transform = new Matrix4f(activeHandProjectionMatrix).mul(state.capturedModelViewMatrix);
        float minClipX = Float.POSITIVE_INFINITY;
        float minClipY = Float.POSITIVE_INFINITY;
        float maxClipX = Float.NEGATIVE_INFINITY;
        float maxClipY = Float.NEGATIVE_INFINITY;
        boolean foundVisibleSample = false;
        for (float[] sample : HAND_RENDER_BOUNDS_SAMPLES) {
            Vector4f clip = transform.transform(new Vector4f(sample[0], sample[1], sample[2], 1.0F));
            float w = clip.w;
            if (!Float.isFinite(w) || Math.abs(w) < 0.0001F) {
                continue;
            }

            float invW = 1.0F / w;
            float ndcX = clip.x * invW;
            float ndcY = clip.y * invW;
            if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
                continue;
            }

            minClipX = Math.min(minClipX, ndcX);
            minClipY = Math.min(minClipY, ndcY);
            maxClipX = Math.max(maxClipX, ndcX);
            maxClipY = Math.max(maxClipY, ndcY);
            foundVisibleSample = true;
        }

        if (!foundVisibleSample) {
            return null;
        }

        int x0 = Mth.floor((Mth.clamp(minClipX, -1.3F, 1.3F) * 0.5F + 0.5F) * mainTarget.width);
        int y0 = Mth.floor((Mth.clamp(minClipY, -1.3F, 1.3F) * 0.5F + 0.5F) * mainTarget.height);
        int x1 = Mth.ceil((Mth.clamp(maxClipX, -1.3F, 1.3F) * 0.5F + 0.5F) * mainTarget.width);
        int y1 = Mth.ceil((Mth.clamp(maxClipY, -1.3F, 1.3F) * 0.5F + 0.5F) * mainTarget.height);
        ScissorRect projectedRect = ScissorRect.fromCorners(x0, y0, x1, y1, mainTarget.width, mainTarget.height);
        if (projectedRect == null) {
            return null;
        }

        ResolvedRenderState renderState = state.capturedRenderState;
        int padding = SCISSOR_BASE_PADDING;
        if (renderState != null) {
            padding += Mth.ceil(renderState.profile().width() * 10.0F + renderState.profile().softness() * 12.0F);
            if (renderState.profile().bloom()) {
                padding += getBloomPadding(renderState.profile());
            }
        }
        return projectedRect.expand(padding, mainTarget.width, mainTarget.height);
    }

    private static void applyScissor(RenderPass pass, @Nullable ScissorRect scissorRect) {
        if (scissorRect != null) {
            pass.enableScissor(scissorRect.x(), scissorRect.y(), scissorRect.width(), scissorRect.height());
        }
    }

    @Nullable
    private static ScissorRect scaleScissorRect(@Nullable ScissorRect scissorRect, RenderTarget sourceTarget, @Nullable RenderTarget target) {
        if (scissorRect == null || sourceTarget == null || target == null) {
            return scissorRect;
        }
        return scissorRect.scale(sourceTarget.width, sourceTarget.height, target.width, target.height);
    }

    private static float[][] createHandRenderBoundsSamples() {
        float min = -1.10F;
        float max = 2.10F;
        float[] axes = new float[]{min, 0.5F, max};
        float[][] samples = new float[axes.length * axes.length * axes.length][3];
        int index = 0;
        for (float x : axes) {
            for (float y : axes) {
                for (float z : axes) {
                    samples[index++] = new float[]{x, y, z};
                }
            }
        }
        return samples;
    }

    private static float[][] resolvePalette(HeldItemOutlineColorSampler.SampledColors sampledColors,
                                            HeldItemOutlineEffectProfile profile) {
        float[][] palette = new float[8][3];
        if (sampledColors.size() <= 0) {
            palette[0] = new float[]{profile.red(), profile.green(), profile.blue()};
            palette[1] = new float[]{profile.secondaryRed(), profile.secondaryGreen(), profile.secondaryBlue()};
            for (int i = 2; i < palette.length; i++) {
                palette[i] = palette[1];
            }
            return palette;
        }

        for (int i = 0; i < palette.length; i++) {
            palette[i] = sampledColors.color(Math.min(i, sampledColors.size() - 1));
        }
        return palette;
    }

    public record HandEffectTarget(InteractionHand hand, HeldItemOutlineEffectProfile profile,
                                   HeldItemOutlineColorSampler.SampledColors sampledColors) {
    }

    private record ResolvedRenderState(HeldItemOutlineEffectProfile profile,
                                       HeldItemOutlineColorSampler.SampledColors sampledColors) {
    }

    @FunctionalInterface
    private interface UniformBufferWriter {
        void write(ByteBuffer buffer);
    }

    private static final class UniformBufferPool {
        private final MappableRingBuffer ringBuffer;
        private final int blockSize;
        private final int capacity;
        private boolean initialized;
        private int nextBlock;

        private UniformBufferPool(String label, int uniformByteSize, int capacity) {
            int alignedBlockSize = Mth.roundToward(uniformByteSize, RenderSystem.getDevice().getUniformOffsetAlignment());
            this.ringBuffer = new MappableRingBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, alignedBlockSize * capacity);
            this.blockSize = alignedBlockSize;
            this.capacity = capacity;
        }

        private void beginFrame() {
            this.nextBlock = 0;
            if (this.initialized) {
                this.ringBuffer.rotate();
            } else {
                this.initialized = true;
            }
        }

        private GpuBufferSlice write(UniformBufferWriter writer) {
            if (this.nextBlock >= this.capacity) {
                throw new IllegalStateException("Uniform buffer pool exhausted for item outline pass");
            }

            GpuBufferSlice slice = this.ringBuffer.currentBuffer().slice((long) this.nextBlock * this.blockSize, this.blockSize);
            this.nextBlock++;
            try (GpuBuffer.MappedView mappedView = RenderSystem.getDevice().createCommandEncoder().mapBuffer(slice, false, true)) {
                ByteBuffer buffer = mappedView.data();
                buffer.position(0);
                writer.write(buffer);
                buffer.position(0);
            }
            return slice;
        }
    }

    private record ScissorRect(int x, int y, int width, int height) {
        @Nullable
        private static ScissorRect fromCorners(int x0, int y0, int x1, int y1, int maxWidth, int maxHeight) {
            int minX = Mth.clamp(Math.min(x0, x1), 0, maxWidth);
            int minY = Mth.clamp(Math.min(y0, y1), 0, maxHeight);
            int maxX = Mth.clamp(Math.max(x0, x1), 0, maxWidth);
            int maxY = Mth.clamp(Math.max(y0, y1), 0, maxHeight);
            int width = maxX - minX;
            int height = maxY - minY;
            return width > 0 && height > 0 ? new ScissorRect(minX, minY, width, height) : null;
        }

        private ScissorRect expand(int padding, int maxWidth, int maxHeight) {
            return fromCorners(this.x - padding, this.y - padding, this.x + this.width + padding, this.y + this.height + padding,
                    maxWidth, maxHeight);
        }

        @Nullable
        private ScissorRect scale(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return null;
            }

            int x0 = Mth.floor(this.x * (targetWidth / (float) sourceWidth));
            int y0 = Mth.floor(this.y * (targetHeight / (float) sourceHeight));
            int x1 = Mth.ceil((this.x + this.width) * (targetWidth / (float) sourceWidth));
            int y1 = Mth.ceil((this.y + this.height) * (targetHeight / (float) sourceHeight));
            return fromCorners(x0, y0, x1, y1, targetWidth, targetHeight);
        }

        @Nullable
        private static ScissorRect union(@Nullable ScissorRect first, @Nullable ScissorRect second) {
            if (first == null) {
                return second;
            }
            if (second == null) {
                return first;
            }
            int minX = Math.min(first.x, second.x);
            int minY = Math.min(first.y, second.y);
            int maxX = Math.max(first.x + first.width, second.x + second.width);
            int maxY = Math.max(first.y + first.height, second.y + second.height);
            return new ScissorRect(minX, minY, maxX - minX, maxY - minY);
        }
    }

    private static final class CaptureState {
        private final InteractionHand hand;
        @Nullable
        private TextureTarget outlineMaskTarget;
        @Nullable
        private RenderBuffers captureRenderBuffers;
        @Nullable
        private FeatureRenderDispatcher captureFeatureDispatcher;
        private boolean capturedThisFrame;
        private boolean capturePreparedThisFrame;
        @Nullable
        private ResolvedRenderState capturedRenderState;
        @Nullable
        private Matrix4f capturedModelViewMatrix;
        @Nullable
        private GpuBufferSlice capturedProjectionMatrix;
        @Nullable
        private ProjectionType capturedProjectionType;
        @Nullable
        private ScissorRect scissorRect;
        private boolean lastObservedHandEnabled = true;
        private ItemStack lastObservedStack = ItemStack.EMPTY;
        private boolean cachedObservedHandEnabled = true;
        private ItemStack cachedObservedResolvedStack = ItemStack.EMPTY;
        private long cachedRuleRevision = -1L;
        @Nullable
        private HeldItemOutlineEffectProfile cachedBaseProfile;
        @Nullable
        private ResolvedRenderState cachedResolvedState;
        @Nullable
        private ResolvedRenderState lastEffectiveState;
        @Nullable
        private ResolvedRenderState pendingState;
        private long transitionEndMillis;

        private CaptureState(InteractionHand hand) {
            this.hand = hand;
        }
    }

    private static final class DuplicatingSubmitNodeStorage extends SubmitNodeStorage {
        private final Minecraft minecraft;
        private final SubmitNodeStorage delegate;

        private DuplicatingSubmitNodeStorage(Minecraft minecraft, SubmitNodeStorage delegate) {
            this.minecraft = minecraft;
            this.delegate = delegate;
        }

        @Override
        public SubmitNodeCollection order(int order) {
            return new DuplicatingSubmitNodeCollection(this.minecraft, this.delegate.order(order), order);
        }

        @Override
        public void submitShadow(com.mojang.blaze3d.vertex.PoseStack poseStack, float shadowRadius, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> shadowPieces) {
            this.delegate.submitShadow(poseStack, shadowRadius, shadowPieces);
        }

        @Override
        public void submitNameTag(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.phys.Vec3 pos, int packedLight, net.minecraft.network.chat.Component text, boolean seeThrough, int backgroundColor, double scale, net.minecraft.client.renderer.state.CameraRenderState cameraRenderState) {
            this.delegate.submitNameTag(poseStack, pos, packedLight, text, seeThrough, backgroundColor, scale, cameraRenderState);
        }

        @Override
        public void submitText(com.mojang.blaze3d.vertex.PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean dropShadow, net.minecraft.client.gui.Font.DisplayMode displayMode, int color, int backgroundColor, int packedLight, int packedOverlay) {
            this.delegate.submitText(poseStack, x, y, text, dropShadow, displayMode, color, backgroundColor, packedLight, packedOverlay);
        }

        @Override
        public void submitFlame(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState renderState, org.joml.Quaternionf quaternion) {
            this.delegate.submitFlame(poseStack, renderState, quaternion);
        }

        @Override
        public void submitLeash(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState leashState) {
            this.delegate.submitLeash(poseStack, leashState);
        }

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, int color, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            this.delegate.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay);
            duplicate(0, collector -> collector.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay));
        }

        @Override
        public void submitModelPart(net.minecraft.client.model.geom.ModelPart modelPart, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, boolean outline, boolean translucent, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int color) {
            this.delegate.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color);
            duplicate(0, collector -> collector.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color));
        }

        @Override
        public void submitBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.level.block.state.BlockState blockState, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlock(poseStack, blockState, packedLight, packedOverlay, color);
            duplicate(0, collector -> collector.submitBlock(poseStack, blockState, packedLight, packedOverlay, color));
        }

        @Override
        public void submitMovingBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState renderState) {
            this.delegate.submitMovingBlock(poseStack, renderState);
            duplicate(0, collector -> collector.submitMovingBlock(poseStack, renderState));
        }

        @Override
        public void submitBlockModel(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.block.model.BlockStateModel blockStateModel, float red, float green, float blue, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color);
            duplicate(0, collector -> collector.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color));
        }

        @Override
        public void submitItem(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.item.ItemDisplayContext displayContext, int packedLight, int packedOverlay, int color, int[] tints, java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foilType) {
            this.delegate.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType);
            duplicate(0, collector -> collector.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType));
        }

        @Override
        public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
            this.delegate.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
            duplicate(0, collector -> collector.submitCustomGeometry(poseStack, renderType, customGeometryRenderer));
        }

        @Override
        public void submitParticleGroup(net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
            this.delegate.submitParticleGroup(particleGroupRenderer);
        }

        @Override
        public void clear() {
            this.delegate.clear();
        }

        @Override
        public void endFrame() {
            this.delegate.endFrame();
        }

        @Override
        public it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap<net.minecraft.client.renderer.SubmitNodeCollection> getSubmitsPerOrder() {
            return this.delegate.getSubmitsPerOrder();
        }

        private void duplicate(int order, java.util.function.Consumer<OrderedSubmitNodeCollector> consumer) {
            OrderedSubmitNodeCollector captureCollector = captureCollectorForCurrentHand(this.minecraft, order);
            if (captureCollector != null) {
                consumer.accept(captureCollector);
                if (activeSubmitHand != null) {
                    stateFor(activeSubmitHand).capturedThisFrame = true;
                }
            }
        }
    }

    private static final class DuplicatingSubmitNodeCollector implements SubmitNodeCollector {
        private final Minecraft minecraft;
        private final SubmitNodeCollector delegate;

        private DuplicatingSubmitNodeCollector(Minecraft minecraft, SubmitNodeCollector delegate) {
            this.minecraft = minecraft;
            this.delegate = delegate;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return new DuplicatingOrderedSubmitNodeCollector(this.minecraft, this.delegate.order(order), order);
        }

        @Override
        public void submitShadow(com.mojang.blaze3d.vertex.PoseStack poseStack, float shadowRadius, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> shadowPieces) {
            this.delegate.submitShadow(poseStack, shadowRadius, shadowPieces);
        }

        @Override
        public void submitNameTag(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.phys.Vec3 pos, int packedLight, net.minecraft.network.chat.Component text, boolean seeThrough, int backgroundColor, double scale, net.minecraft.client.renderer.state.CameraRenderState cameraRenderState) {
            this.delegate.submitNameTag(poseStack, pos, packedLight, text, seeThrough, backgroundColor, scale, cameraRenderState);
        }

        @Override
        public void submitText(com.mojang.blaze3d.vertex.PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean dropShadow, net.minecraft.client.gui.Font.DisplayMode displayMode, int color, int backgroundColor, int packedLight, int packedOverlay) {
            this.delegate.submitText(poseStack, x, y, text, dropShadow, displayMode, color, backgroundColor, packedLight, packedOverlay);
        }

        @Override
        public void submitFlame(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState renderState, org.joml.Quaternionf quaternion) {
            this.delegate.submitFlame(poseStack, renderState, quaternion);
        }

        @Override
        public void submitLeash(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState leashState) {
            this.delegate.submitLeash(poseStack, leashState);
        }

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, int color, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            this.delegate.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay);
            duplicate(0, collector -> collector.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay));
        }

        @Override
        public void submitModelPart(net.minecraft.client.model.geom.ModelPart modelPart, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, boolean outline, boolean translucent, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int color) {
            this.delegate.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color);
            duplicate(0, collector -> collector.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color));
        }

        @Override
        public void submitBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.level.block.state.BlockState blockState, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlock(poseStack, blockState, packedLight, packedOverlay, color);
            duplicate(0, collector -> collector.submitBlock(poseStack, blockState, packedLight, packedOverlay, color));
        }

        @Override
        public void submitMovingBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState renderState) {
            this.delegate.submitMovingBlock(poseStack, renderState);
            duplicate(0, collector -> collector.submitMovingBlock(poseStack, renderState));
        }

        @Override
        public void submitBlockModel(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.block.model.BlockStateModel blockStateModel, float red, float green, float blue, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color);
            duplicate(0, collector -> collector.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color));
        }

        @Override
        public void submitItem(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.item.ItemDisplayContext displayContext, int packedLight, int packedOverlay, int color, int[] tints, java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foilType) {
            this.delegate.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType);
            duplicate(0, collector -> collector.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType));
        }

        @Override
        public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
            this.delegate.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
            duplicate(0, collector -> collector.submitCustomGeometry(poseStack, renderType, customGeometryRenderer));
        }

        @Override
        public void submitParticleGroup(net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
            this.delegate.submitParticleGroup(particleGroupRenderer);
        }

        private void duplicate(int order, java.util.function.Consumer<OrderedSubmitNodeCollector> consumer) {
            OrderedSubmitNodeCollector captureCollector = captureCollectorForCurrentHand(this.minecraft, order);
            if (captureCollector != null) {
                consumer.accept(captureCollector);
                if (activeSubmitHand != null) {
                    stateFor(activeSubmitHand).capturedThisFrame = true;
                }
            }
        }
    }

    private static final class DuplicatingSubmitNodeCollection extends SubmitNodeCollection {
        private final Minecraft minecraft;
        private final SubmitNodeCollection delegate;
        private final int order;

        private DuplicatingSubmitNodeCollection(Minecraft minecraft, SubmitNodeCollection delegate, int order) {
            super(new SubmitNodeStorage());
            this.minecraft = minecraft;
            this.delegate = delegate;
            this.order = order;
        }

        @Override
        public void submitShadow(com.mojang.blaze3d.vertex.PoseStack poseStack, float shadowRadius, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> shadowPieces) {
            this.delegate.submitShadow(poseStack, shadowRadius, shadowPieces);
        }

        @Override
        public void submitNameTag(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.phys.Vec3 pos, int packedLight, net.minecraft.network.chat.Component text, boolean seeThrough, int backgroundColor, double scale, net.minecraft.client.renderer.state.CameraRenderState cameraRenderState) {
            this.delegate.submitNameTag(poseStack, pos, packedLight, text, seeThrough, backgroundColor, scale, cameraRenderState);
        }

        @Override
        public void submitText(com.mojang.blaze3d.vertex.PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean dropShadow, net.minecraft.client.gui.Font.DisplayMode displayMode, int color, int backgroundColor, int packedLight, int packedOverlay) {
            this.delegate.submitText(poseStack, x, y, text, dropShadow, displayMode, color, backgroundColor, packedLight, packedOverlay);
        }

        @Override
        public void submitFlame(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState renderState, org.joml.Quaternionf quaternion) {
            this.delegate.submitFlame(poseStack, renderState, quaternion);
        }

        @Override
        public void submitLeash(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState leashState) {
            this.delegate.submitLeash(poseStack, leashState);
        }

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, int color, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            this.delegate.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay);
            duplicate(collector -> collector.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay));
        }

        @Override
        public void submitModelPart(net.minecraft.client.model.geom.ModelPart modelPart, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, boolean outline, boolean translucent, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int color) {
            this.delegate.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color);
            duplicate(collector -> collector.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color));
        }

        @Override
        public void submitBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.level.block.state.BlockState blockState, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlock(poseStack, blockState, packedLight, packedOverlay, color);
            duplicate(collector -> collector.submitBlock(poseStack, blockState, packedLight, packedOverlay, color));
        }

        @Override
        public void submitMovingBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState renderState) {
            this.delegate.submitMovingBlock(poseStack, renderState);
            duplicate(collector -> collector.submitMovingBlock(poseStack, renderState));
        }

        @Override
        public void submitBlockModel(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.block.model.BlockStateModel blockStateModel, float red, float green, float blue, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color);
            duplicate(collector -> collector.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color));
        }

        @Override
        public void submitItem(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.item.ItemDisplayContext displayContext, int packedLight, int packedOverlay, int color, int[] tints, java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foilType) {
            this.delegate.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType);
            duplicate(collector -> collector.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType));
        }

        @Override
        public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
            this.delegate.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
            duplicate(collector -> collector.submitCustomGeometry(poseStack, renderType, customGeometryRenderer));
        }

        @Override
        public void submitParticleGroup(net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
            this.delegate.submitParticleGroup(particleGroupRenderer);
        }

        private void duplicate(java.util.function.Consumer<OrderedSubmitNodeCollector> consumer) {
            OrderedSubmitNodeCollector captureCollector = captureCollectorForCurrentHand(this.minecraft, this.order);
            if (captureCollector != null) {
                consumer.accept(captureCollector);
                if (activeSubmitHand != null) {
                    stateFor(activeSubmitHand).capturedThisFrame = true;
                }
            }
        }
    }

    private static final class DuplicatingOrderedSubmitNodeCollector implements OrderedSubmitNodeCollector {
        private final Minecraft minecraft;
        private final OrderedSubmitNodeCollector delegate;
        private final int order;

        private DuplicatingOrderedSubmitNodeCollector(Minecraft minecraft, OrderedSubmitNodeCollector delegate, int order) {
            this.minecraft = minecraft;
            this.delegate = delegate;
            this.order = order;
        }

        @Override
        public void submitShadow(com.mojang.blaze3d.vertex.PoseStack poseStack, float shadowRadius, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> shadowPieces) {
            this.delegate.submitShadow(poseStack, shadowRadius, shadowPieces);
        }

        @Override
        public void submitNameTag(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.phys.Vec3 pos, int packedLight, net.minecraft.network.chat.Component text, boolean seeThrough, int backgroundColor, double scale, net.minecraft.client.renderer.state.CameraRenderState cameraRenderState) {
            this.delegate.submitNameTag(poseStack, pos, packedLight, text, seeThrough, backgroundColor, scale, cameraRenderState);
        }

        @Override
        public void submitText(com.mojang.blaze3d.vertex.PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean dropShadow, net.minecraft.client.gui.Font.DisplayMode displayMode, int color, int backgroundColor, int packedLight, int packedOverlay) {
            this.delegate.submitText(poseStack, x, y, text, dropShadow, displayMode, color, backgroundColor, packedLight, packedOverlay);
        }

        @Override
        public void submitFlame(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState renderState, org.joml.Quaternionf quaternion) {
            this.delegate.submitFlame(poseStack, renderState, quaternion);
        }

        @Override
        public void submitLeash(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState leashState) {
            this.delegate.submitLeash(poseStack, leashState);
        }

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, int color, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            this.delegate.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay);
            duplicate(collector -> collector.submitModel(model, state, poseStack, renderType, packedLight, packedOverlay, color, textureAtlasSprite, packedCrumblingColor, crumblingOverlay));
        }

        @Override
        public void submitModelPart(net.minecraft.client.model.geom.ModelPart modelPart, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, int packedLight, int packedOverlay, net.minecraft.client.renderer.texture.TextureAtlasSprite textureAtlasSprite, boolean outline, boolean translucent, int packedCrumblingColor, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int color) {
            this.delegate.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color);
            duplicate(collector -> collector.submitModelPart(modelPart, poseStack, renderType, packedLight, packedOverlay, textureAtlasSprite, outline, translucent, packedCrumblingColor, crumblingOverlay, color));
        }

        @Override
        public void submitBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.level.block.state.BlockState blockState, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlock(poseStack, blockState, packedLight, packedOverlay, color);
            duplicate(collector -> collector.submitBlock(poseStack, blockState, packedLight, packedOverlay, color));
        }

        @Override
        public void submitMovingBlock(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState renderState) {
            this.delegate.submitMovingBlock(poseStack, renderState);
            duplicate(collector -> collector.submitMovingBlock(poseStack, renderState));
        }

        @Override
        public void submitBlockModel(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.block.model.BlockStateModel blockStateModel, float red, float green, float blue, int packedLight, int packedOverlay, int color) {
            this.delegate.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color);
            duplicate(collector -> collector.submitBlockModel(poseStack, renderType, blockStateModel, red, green, blue, packedLight, packedOverlay, color));
        }

        @Override
        public void submitItem(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.item.ItemDisplayContext displayContext, int packedLight, int packedOverlay, int color, int[] tints, java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foilType) {
            this.delegate.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType);
            duplicate(collector -> collector.submitItem(poseStack, displayContext, packedLight, packedOverlay, color, tints, quads, renderType, foilType));
        }

        @Override
        public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.rendertype.RenderType renderType, net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
            this.delegate.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
            duplicate(collector -> collector.submitCustomGeometry(poseStack, renderType, customGeometryRenderer));
        }

        @Override
        public void submitParticleGroup(net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {
            this.delegate.submitParticleGroup(particleGroupRenderer);
        }

        private void duplicate(java.util.function.Consumer<OrderedSubmitNodeCollector> consumer) {
            OrderedSubmitNodeCollector captureCollector = captureCollectorForCurrentHand(this.minecraft, this.order);
            if (captureCollector != null) {
                consumer.accept(captureCollector);
                if (activeSubmitHand != null) {
                    stateFor(activeSubmitHand).capturedThisFrame = true;
                }
            }
        }
    }
}
