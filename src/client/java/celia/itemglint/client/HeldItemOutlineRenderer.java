package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.objects.Object2ObjectSortedMaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL30C;

import java.util.ArrayList;
import java.util.List;

public final class HeldItemOutlineRenderer {
    private static final long RULE_SWITCH_DELAY_MILLIS = 200L;
    private static final long ANIMATION_TIME_WRAP_TICKS = 240000L;
    private static final float BLOOM_BLUR_PASS_RADIUS = 2.5F;
    private static final float BLOOM_BLUR_KERNEL_RADIUS = 6.9230769F;
    private static final float SMALL_BLOOM_FAST_PATH_RADIUS = 1.0F;
    private static final int MIN_BLOOM_TARGET_SIZE = 64;
    private static final int SCISSOR_BASE_PADDING = 8;
    private static final int SCISSOR_BLOOM_PADDING = 4;
    private static final float[][] HAND_RENDER_BOUNDS_SAMPLES = createHandRenderBoundsSamples();

    private static final CaptureState MAIN_HAND_STATE = new CaptureState(InteractionHand.MAIN_HAND);
    private static final CaptureState OFF_HAND_STATE = new CaptureState(InteractionHand.OFF_HAND);

    @Nullable
    private static CaptureState activeCaptureState;
    @Nullable
    private static InteractionHand activeSubmitHand;
    @Nullable
    private static Matrix4f activeHandProjectionMatrix;
    private static int duplicationSuppressDepth;
    private static long irisDebugNextLogMillis;
    private static int irisDebugRedirectCount;
    private static int irisDebugWrappedCount;
    private static int irisDebugPrepareFailureCount;
    private static int irisDebugDuplicateCount;
    private static int irisDebugFlushCount;
    private static int irisDebugCompositeCount;
    @Nullable
    private static TextureTarget bloomBlurTargetA;
    @Nullable
    private static TextureTarget bloomBlurTargetB;
    @Nullable
    private static TextureTarget bloomBlurNearTarget;

    private HeldItemOutlineRenderer() {
    }

    public static boolean shouldRenderOutlinePass(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        return HeldItemOutlineShaderRegistry.getShader() != null
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

    public static void beginItemInHandRender(@Nullable Matrix4f projectionMatrix) {
        activeHandProjectionMatrix = projectionMatrix == null ? null : new Matrix4f(projectionMatrix);
    }

    public static void endItemInHandRender() {
        activeSubmitHand = null;
        activeHandProjectionMatrix = null;
        duplicationSuppressDepth = 0;
    }

    public static boolean shouldBatchHands(@Nullable HandEffectTarget current, @Nullable HandEffectTarget next) {
        if (current == null || next == null) {
            return false;
        }

        return current.profile().equals(next.profile())
                && sameSampledColors(current.sampledColors(), next.sampledColors());
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

    public static void beginDuplicationSuppress() {
        duplicationSuppressDepth++;
    }

    public static void endDuplicationSuppress() {
        if (duplicationSuppressDepth > 0) {
            duplicationSuppressDepth--;
        }
    }

    public static MultiBufferSource.BufferSource wrapIrisHandBufferSource(Minecraft minecraft, RenderTarget mainTarget,
                                                                          MultiBufferSource.BufferSource delegate,
                                                                          LocalPlayer player) {
        if (!prepareCompatCaptureStates(minecraft, mainTarget, player)) {
            irisDebugPrepareFailureCount++;
            logIrisDebugStatus("wrap-skipped", minecraft, player, true);
            return delegate;
        }

        irisDebugWrappedCount++;
        logIrisDebugStatus("wrap-ready", minecraft, player, false);
        return new DuplicatingBufferSource(delegate);
    }

    public static void flushIrisCompatCaptureBuffers(RenderTarget mainTarget) {
        // Shader-pack rendering captures hand vertices into a compat buffer and replays them after Iris finishes
        // the level render, so callers that still invoke this method should not flush immediately.
    }

    public static boolean beginCapture(Minecraft minecraft, RenderTarget mainTarget, InteractionHand stateHand,
                                       @Nullable InteractionHand handFilter, @Nullable Matrix4f modelViewMatrix,
                                       HeldItemOutlineEffectProfile profile,
                                       HeldItemOutlineColorSampler.SampledColors sampledColors) {
        ShaderInstance shader = HeldItemOutlineShaderRegistry.getShader();
        if (shader == null || minecraft.level == null || minecraft.player == null || !shouldRenderOutline(minecraft)) {
            clearAllFrameStates();
            return false;
        }

        CaptureState state = stateFor(stateHand);
        state.resetImmediateFrameState();
        state.captureHandFilter = handFilter;
        state.capturedRenderState = new ResolvedRenderState(profile, sampledColors);
        state.capturedModelViewMatrix = modelViewMatrix == null ? null : new Matrix4f(modelViewMatrix);
        state.scissorRect = handFilter != null ? resolveHandScissorRect(mainTarget, state.capturedModelViewMatrix, state.capturedRenderState) : null;
        ensureOutlineMaskTarget(mainTarget, state);
        if (state.outlineMaskTarget == null) {
            clearAllFrameStates();
            return false;
        }

        state.outlineMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        state.outlineMaskTarget.clear(Minecraft.ON_OSX);
        state.outlineMaskTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        state.compatCapture = false;
        state.restoreTarget = mainTarget;
        state.captureActive = true;
        activeCaptureState = state;
        return true;
    }

    public static void endCapture() {
        CaptureState state = activeCaptureState;
        if (state == null || !state.captureActive || state.restoreTarget == null) {
            if (state != null) {
                state.captureActive = false;
                state.restoreTarget = null;
            }
            activeCaptureState = null;
            return;
        }

        if (state.compatCapture) {
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.restoreFramebufferId);
            GlStateManager._viewport(state.restoreViewportX, state.restoreViewportY, state.restoreViewportWidth, state.restoreViewportHeight);
        } else {
            state.restoreTarget.bindWrite(true);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        state.captureActive = false;
        state.restoreTarget = null;
        state.compatCapture = false;
        activeCaptureState = null;
    }

    public static void markHandCaptured() {
        if (activeCaptureState != null && activeCaptureState.captureActive) {
            activeCaptureState.capturedThisFrame = true;
            activeCaptureState.capturedHandCount++;
        }
    }

    public static boolean hasPendingComposites() {
        return hasPendingComposite(MAIN_HAND_STATE) || hasPendingComposite(OFF_HAND_STATE);
    }

    public static void renderPendingComposites(Minecraft minecraft, RenderTarget mainTarget) {
        renderPendingComposite(minecraft, mainTarget, MAIN_HAND_STATE);
        renderPendingComposite(minecraft, mainTarget, OFF_HAND_STATE);
    }

    public static void composite(Minecraft minecraft, RenderTarget mainTarget, InteractionHand hand) {
        CaptureState state = stateFor(hand);
        if (!hasPendingComposite(state)) {
            state.resetAfterComposite();
            return;
        }

        compositeCapturedState(minecraft, mainTarget, state);
        state.resetAfterComposite();
    }

    public static boolean isCaptureActive() {
        return activeCaptureState != null && activeCaptureState.captureActive;
    }

    public static void noteIrisRedirect(Minecraft minecraft, @Nullable LocalPlayer player, String stage) {
        irisDebugRedirectCount++;
        logIrisDebugStatus(stage, minecraft, player, false);
    }

    public static boolean shouldSkipHand(InteractionHand hand) {
        return activeCaptureState != null
                && activeCaptureState.captureActive
                && activeCaptureState.captureHandFilter != null
                && activeCaptureState.captureHandFilter != hand;
    }

    private static boolean shouldRenderOutline(Minecraft minecraft) {
        if (!minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (minecraft.options.hideGui) {
            return false;
        }
        if (minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return false;
        }
        return !(minecraft.getCameraEntity() instanceof net.minecraft.world.entity.LivingEntity livingEntity
                && livingEntity.isSleeping());
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

    private static CaptureState stateFor(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? MAIN_HAND_STATE : OFF_HAND_STATE;
    }

    private static HeldItemOutlineColorSampler.SampledColors resolveSampledColors(ItemStack stack, HeldItemOutlineEffectProfile profile) {
        return profile.colorMode() == HeldItemOutlineSettings.ColorMode.AUTO_SAMPLE_SCROLL
                ? HeldItemOutlineColorSampler.sample(stack, profile)
                : HeldItemOutlineColorSampler.SampledColors.EMPTY;
    }

    private static void ensureOutlineMaskTarget(RenderTarget mainTarget, CaptureState state) {
        if (state.outlineMaskTarget == null) {
            state.outlineMaskTarget = new TextureTarget(mainTarget.width, mainTarget.height, true, Minecraft.ON_OSX);
            state.outlineMaskTarget.setFilterMode(9729);
        } else if (state.outlineMaskTarget.width != mainTarget.width || state.outlineMaskTarget.height != mainTarget.height) {
            state.outlineMaskTarget.resize(mainTarget.width, mainTarget.height, Minecraft.ON_OSX);
            state.outlineMaskTarget.setFilterMode(9729);
        }
    }

    private static void ensureBloomTargets(RenderTarget mainTarget, HeldItemOutlineEffectProfile profile) {
        int bloomWidth = getBloomTargetDimension(mainTarget.width, profile);
        int bloomHeight = getBloomTargetDimension(mainTarget.height, profile);

        if (bloomBlurTargetA == null) {
            bloomBlurTargetA = new TextureTarget(bloomWidth, bloomHeight, false, Minecraft.ON_OSX);
            bloomBlurTargetA.setFilterMode(9729);
        } else if (bloomBlurTargetA.width != bloomWidth || bloomBlurTargetA.height != bloomHeight) {
            bloomBlurTargetA.resize(bloomWidth, bloomHeight, Minecraft.ON_OSX);
            bloomBlurTargetA.setFilterMode(9729);
        }

        if (bloomBlurTargetB == null) {
            bloomBlurTargetB = new TextureTarget(bloomWidth, bloomHeight, false, Minecraft.ON_OSX);
            bloomBlurTargetB.setFilterMode(9729);
        } else if (bloomBlurTargetB.width != bloomWidth || bloomBlurTargetB.height != bloomHeight) {
            bloomBlurTargetB.resize(bloomWidth, bloomHeight, Minecraft.ON_OSX);
            bloomBlurTargetB.setFilterMode(9729);
        }

        if (bloomBlurNearTarget == null) {
            bloomBlurNearTarget = new TextureTarget(bloomWidth, bloomHeight, false, Minecraft.ON_OSX);
            bloomBlurNearTarget.setFilterMode(9729);
        } else if (bloomBlurNearTarget.width != bloomWidth || bloomBlurNearTarget.height != bloomHeight) {
            bloomBlurNearTarget.resize(bloomWidth, bloomHeight, Minecraft.ON_OSX);
            bloomBlurNearTarget.setFilterMode(9729);
        }
    }
    private static void applyOutlineUniforms(Minecraft minecraft, RenderTarget mainTarget, ShaderInstance outlineShader,
                                             TextureTarget outlineMaskTarget, HeldItemOutlineEffectProfile profile,
                                             HeldItemOutlineColorSampler.SampledColors sampledColors) {
        outlineShader.setSampler("DiffuseSampler", outlineMaskTarget.getColorTextureId());
        outlineShader.setSampler("DepthSampler", outlineMaskTarget.getDepthTextureId());
        if (outlineShader.getUniform("ScreenSize") != null) {
            outlineShader.getUniform("ScreenSize").set((float) mainTarget.width, (float) mainTarget.height);
        }
        if (outlineShader.getUniform("OutlineColor") != null) {
            outlineShader.getUniform("OutlineColor").set(profile.red(), profile.green(), profile.blue(), 1.0F);
        }
        if (outlineShader.getUniform("SecondaryColor") != null) {
            outlineShader.getUniform("SecondaryColor").set(profile.secondaryRed(), profile.secondaryGreen(), profile.secondaryBlue(), 1.0F);
        }
        applySamplePalette(outlineShader, sampledColors, profile);
        if (outlineShader.getUniform("ColorMode") != null) {
            outlineShader.getUniform("ColorMode").set(profile.colorMode().shaderValue());
        }
        if (outlineShader.getUniform("ColorScrollSpeed") != null) {
            outlineShader.getUniform("ColorScrollSpeed").set(profile.colorScrollSpeed());
        }
        if (outlineShader.getUniform("OutlineWidth") != null) {
            outlineShader.getUniform("OutlineWidth").set(profile.width());
        }
        if (outlineShader.getUniform("Softness") != null) {
            outlineShader.getUniform("Softness").set(profile.softness());
        }
        if (outlineShader.getUniform("AlphaThreshold") != null) {
            outlineShader.getUniform("AlphaThreshold").set(profile.alphaThreshold());
        }
        if (outlineShader.getUniform("Opacity") != null) {
            outlineShader.getUniform("Opacity").set(profile.opacity());
        }
        if (outlineShader.getUniform("DepthWeight") != null) {
            outlineShader.getUniform("DepthWeight").set(profile.depthWeight());
        }
        if (outlineShader.getUniform("GlowStrength") != null) {
            outlineShader.getUniform("GlowStrength").set(profile.glowStrength());
        }
        if (outlineShader.getUniform("Time") != null) {
            outlineShader.getUniform("Time").set(resolveAnimationTime(minecraft));
        }
    }

    private static void clearAllFrameStates() {
        MAIN_HAND_STATE.resetAfterComposite();
        OFF_HAND_STATE.resetAfterComposite();
        activeCaptureState = null;
        activeSubmitHand = null;
        activeHandProjectionMatrix = null;
        duplicationSuppressDepth = 0;
    }

    private static void logIrisDebugStatus(String stage, Minecraft minecraft, @Nullable LocalPlayer player, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < irisDebugNextLogMillis) {
            return;
        }

        irisDebugNextLogMillis = now + 2000L;
        int targetCount = player == null ? -1 : getRenderableHands(player).size();
        boolean mainPending = hasPendingComposite(MAIN_HAND_STATE);
        boolean offPending = hasPendingComposite(OFF_HAND_STATE);
        String message = String.format(
                "[HeldItemOutline][IrisDebug] stage=%s shaderPack=%s shadowPass=%s targets=%d redirect=%d wrap=%d duplicate=%d flush=%d composite=%d fail=%d mainPrepared=%s offPrepared=%s mainPending=%s offPending=%s",
                stage,
                HeldItemOutlineCompat.isIrisShaderPackActive(),
                HeldItemOutlineCompat.isIrisShadowPass(),
                targetCount,
                irisDebugRedirectCount,
                irisDebugWrappedCount,
                irisDebugDuplicateCount,
                irisDebugFlushCount,
                irisDebugCompositeCount,
                irisDebugPrepareFailureCount,
                MAIN_HAND_STATE.compatFramePrepared,
                OFF_HAND_STATE.compatFramePrepared,
                mainPending,
                offPending
        );
        System.out.println(message);
        ItemGlint.LOGGER.warn(message);
    }

    private static boolean prepareCompatCaptureStates(Minecraft minecraft, RenderTarget mainTarget, LocalPlayer player) {
        ShaderInstance shader = HeldItemOutlineShaderRegistry.getShader();
        if (shader == null || minecraft.level == null || player == null || !shouldRenderOutline(minecraft)) {
            clearAllFrameStates();
            return false;
        }

        boolean hasCaptureTarget = false;
        boolean mainPrepared = false;
        boolean offPrepared = false;
        for (HandEffectTarget target : getRenderableHands(player)) {
            CaptureState state = stateFor(target.hand());
            prepareCompatCaptureState(mainTarget, state, target.profile(), target.sampledColors());
            hasCaptureTarget = true;
            if (target.hand() == InteractionHand.MAIN_HAND) {
                mainPrepared = true;
            } else {
                offPrepared = true;
            }
        }

        if (!mainPrepared && !MAIN_HAND_STATE.compatFramePrepared) {
            MAIN_HAND_STATE.capturedRenderState = null;
        }
        if (!offPrepared && !OFF_HAND_STATE.compatFramePrepared) {
            OFF_HAND_STATE.capturedRenderState = null;
        }
        return hasCaptureTarget;
    }

    private static void prepareCompatCaptureState(RenderTarget mainTarget, CaptureState state,
                                                  HeldItemOutlineEffectProfile profile,
                                                  HeldItemOutlineColorSampler.SampledColors sampledColors) {
        state.capturedRenderState = new ResolvedRenderState(profile, sampledColors);
        ensureOutlineMaskTarget(mainTarget, state);
        if (state.outlineMaskTarget == null) {
            return;
        }

        if (!state.compatFramePrepared) {
            state.capturedThisFrame = false;
            state.capturedHandCount = 0;
            state.compatPendingFlush = false;
            state.capturedModelViewMatrix = null;
            state.scissorRect = null;
            state.outlineMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            state.outlineMaskTarget.clear(Minecraft.ON_OSX);
            state.compatFramePrepared = true;
        }
    }

    @Nullable
    private static MultiBufferSource.BufferSource captureBufferSourceForCurrentHand() {
        if (activeSubmitHand == null || duplicationSuppressDepth > 0) {
            return null;
        }

        CaptureState state = stateFor(activeSubmitHand);
        if (!hasCompatCaptureTarget(state)) {
            return null;
        }

        return getCompatCaptureBufferSource(state);
    }

    private static boolean hasCompatCaptureTarget(CaptureState state) {
        return state.capturedRenderState != null && state.outlineMaskTarget != null && state.compatFramePrepared;
    }

    private static MultiBufferSource.BufferSource getCompatCaptureBufferSource(CaptureState state) {
        if (state.compatCaptureRenderBuffers == null) {
            state.compatCaptureRenderBuffers = new RenderBuffers(1);
        }
        return state.compatCaptureRenderBuffers.bufferSource();
    }

    private static void captureActiveHandRenderMatrices() {
        if (activeSubmitHand == null) {
            return;
        }

        CaptureState state = stateFor(activeSubmitHand);
        if (!hasCompatCaptureTarget(state)) {
            return;
        }

        state.capturedModelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        state.capturedProjectionMatrix = activeHandProjectionMatrix == null ? null : new Matrix4f(activeHandProjectionMatrix);
        state.scissorRect = resolveHandScissorRect(Minecraft.getInstance().getMainRenderTarget(),
                state.capturedModelViewMatrix, state.capturedRenderState);
    }

    private static void markCurrentHandCompatCaptured() {
        if (activeSubmitHand == null) {
            return;
        }

        CaptureState state = stateFor(activeSubmitHand);
        if (!hasCompatCaptureTarget(state)) {
            return;
        }

        state.capturedThisFrame = true;
        state.compatPendingFlush = true;
        state.capturedHandCount++;
        irisDebugDuplicateCount++;
    }

    private static void flushIrisCompatCaptureBuffer(RenderTarget mainTarget, CaptureState state) {
        if (!state.compatPendingFlush || state.outlineMaskTarget == null || state.compatCaptureRenderBuffers == null) {
            state.compatPendingFlush = false;
            return;
        }

        boolean restoreProjection = state.capturedProjectionMatrix != null;
        boolean restoreModelView = state.capturedModelViewMatrix != null;
        state.outlineMaskTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        if (restoreProjection) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(state.capturedProjectionMatrix, VertexSorting.DISTANCE_TO_ORIGIN);
        }
        if (restoreModelView) {
            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.getModelViewStack().set(state.capturedModelViewMatrix);
            RenderSystem.applyModelViewMatrix();
        }
        try {
            state.compatCaptureRenderBuffers.bufferSource().endBatch();
        } finally {
            if (restoreModelView) {
                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
            if (restoreProjection) {
                RenderSystem.restoreProjectionMatrix();
            }
        }

        irisDebugFlushCount++;
        state.compatPendingFlush = false;
    }

    private static boolean hasPendingComposite(CaptureState state) {
        return state.capturedThisFrame && state.outlineMaskTarget != null && state.capturedRenderState != null;
    }

    private static void renderPendingComposite(Minecraft minecraft, RenderTarget mainTarget, CaptureState state) {
        if (!hasPendingComposite(state)) {
            state.resetAfterComposite();
            return;
        }

        flushIrisCompatCaptureBuffer(mainTarget, state);
        compositeCapturedState(minecraft, mainTarget, state);
        state.resetAfterComposite();
    }

    private static void compositeCapturedState(Minecraft minecraft, RenderTarget mainTarget, CaptureState state) {
        ResolvedRenderState renderState = state.capturedRenderState;
        ShaderInstance outlineShader = HeldItemOutlineShaderRegistry.getShader();
        if (outlineShader == null || state.outlineMaskTarget == null || renderState == null) {
            return;
        }

        if (HeldItemOutlineCompat.shouldUseSodiumIrisPipeline(minecraft) && state.compatFramePrepared) {
            irisDebugCompositeCount++;
            logIrisDebugStatus("composite", minecraft, minecraft.player, false);
        }

        mainTarget.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        applyOutlineUniforms(minecraft, mainTarget, outlineShader, state.outlineMaskTarget,
                renderState.profile(), renderState.sampledColors());
        state.outlineMaskTarget.setFilterMode(9728);
        applyScissor(state.scissorRect);
        drawFullscreenQuad(outlineShader);
        clearScissor();

        if (renderState.profile().bloom()) {
            compositeBloom(minecraft, mainTarget, state.outlineMaskTarget,
                    renderState.profile(), renderState.sampledColors(), state.scissorRect);
        }

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static void compositeBloom(Minecraft minecraft, RenderTarget mainTarget, TextureTarget outlineMaskTarget,
                                       HeldItemOutlineEffectProfile profile, HeldItemOutlineColorSampler.SampledColors sampledColors,
                                       @Nullable ScissorRect scissorRect) {
        ShaderInstance blurShader = HeldItemBloomBlurShaderRegistry.getShader();
        ShaderInstance bloomShader = HeldItemBloomShaderRegistry.getShader();
        if (blurShader == null || bloomShader == null) {
            return;
        }
        ensureBloomTargets(mainTarget, profile);
        if (bloomBlurTargetA == null || bloomBlurTargetB == null || bloomBlurNearTarget == null) {
            return;
        }

        RenderSystem.disableBlend();
        outlineMaskTarget.setFilterMode(9729);
        if (blurShader.getUniform("ScreenSize") != null) {
            blurShader.getUniform("ScreenSize").set((float) bloomBlurTargetA.width, (float) bloomBlurTargetA.height);
        }

        float nearBlurRadius = getNearBlurRadius(profile);
        float farBlurRadius = getFarBlurRadius(profile);
        int farBlurPasses = getFarBlurPasses(profile, farBlurRadius);
        float farPassRadius = farBlurRadius / farBlurPasses;
        ScissorRect bloomScissorRect = scaleScissorRect(scissorRect, outlineMaskTarget, bloomBlurTargetA);

        int nearTexture = applyBlurChain(blurShader, outlineMaskTarget.getColorTextureId(), bloomBlurTargetA,
                bloomBlurNearTarget, nearBlurRadius, 1, bloomScissorRect);
        int farTexture = shouldUseNearOnlyBloom(profile, farBlurPasses)
                ? nearTexture
                : applyBlurChain(blurShader, outlineMaskTarget.getColorTextureId(), bloomBlurTargetA,
                bloomBlurTargetB, farPassRadius, farBlurPasses, bloomScissorRect);

        mainTarget.bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        bloomShader.setSampler("DiffuseSampler", outlineMaskTarget.getColorTextureId());
        bloomShader.setSampler("NearBlurSampler", nearTexture);
        bloomShader.setSampler("FarBlurSampler", farTexture);
        if (bloomShader.getUniform("OutlineColor") != null) {
            bloomShader.getUniform("OutlineColor").set(profile.red(), profile.green(), profile.blue(), 1.0F);
        }
        if (bloomShader.getUniform("SecondaryColor") != null) {
            bloomShader.getUniform("SecondaryColor").set(profile.secondaryRed(), profile.secondaryGreen(), profile.secondaryBlue(), 1.0F);
        }
        applySamplePalette(bloomShader, sampledColors, profile);
        if (bloomShader.getUniform("ColorMode") != null) {
            bloomShader.getUniform("ColorMode").set(profile.colorMode().shaderValue());
        }
        if (bloomShader.getUniform("ColorScrollSpeed") != null) {
            bloomShader.getUniform("ColorScrollSpeed").set(profile.colorScrollSpeed());
        }
        if (bloomShader.getUniform("AlphaThreshold") != null) {
            bloomShader.getUniform("AlphaThreshold").set(profile.alphaThreshold());
        }
        if (bloomShader.getUniform("Opacity") != null) {
            bloomShader.getUniform("Opacity").set(profile.opacity());
        }
        if (bloomShader.getUniform("GlowStrength") != null) {
            bloomShader.getUniform("GlowStrength").set(profile.glowStrength());
        }
        if (bloomShader.getUniform("Time") != null) {
            bloomShader.getUniform("Time").set(resolveAnimationTime(minecraft));
        }
        if (bloomShader.getUniform("BloomStrength") != null) {
            bloomShader.getUniform("BloomStrength").set(profile.bloomStrength());
        }
        if (bloomShader.getUniform("BloomRadius") != null) {
            bloomShader.getUniform("BloomRadius").set(profile.bloomRadius());
        }
        applyScissor(scissorRect);
        drawFullscreenQuad(bloomShader);
        clearScissor();
    }

    private static int getBloomTargetDimension(int fullSize, HeldItemOutlineEffectProfile profile) {
        int downsampleFactor = profile.bloomResolution().downsampleFactor();
        return Math.max(MIN_BLOOM_TARGET_SIZE, Math.max(1, fullSize / Math.max(1, downsampleFactor)));
    }

    private static int applyBlurChain(ShaderInstance blurShader, int sourceTexture, TextureTarget tempTarget,
                                      TextureTarget destinationTarget, float blurRadius, int passes,
                                      @Nullable ScissorRect scissorRect) {
        int currentTexture = sourceTexture;
        for (int i = 0; i < passes; i++) {
            if (blurShader.getUniform("BlurRadius") != null) {
                blurShader.getUniform("BlurRadius").set(blurRadius);
            }

            tempTarget.bindWrite(true);
            blurShader.setSampler("DiffuseSampler", currentTexture);
            if (blurShader.getUniform("BlurDirection") != null) {
                blurShader.getUniform("BlurDirection").set(1.0F, 0.0F);
            }
            applyScissor(scissorRect);
            drawFullscreenQuad(blurShader);
            clearScissor();

            destinationTarget.bindWrite(true);
            blurShader.setSampler("DiffuseSampler", tempTarget.getColorTextureId());
            if (blurShader.getUniform("BlurDirection") != null) {
                blurShader.getUniform("BlurDirection").set(0.0F, 1.0F);
            }
            applyScissor(scissorRect);
            drawFullscreenQuad(blurShader);
            clearScissor();

            currentTexture = destinationTarget.getColorTextureId();
        }
        return currentTexture;
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
    private static ScissorRect resolveHandScissorRect(RenderTarget mainTarget, @Nullable Matrix4f modelViewMatrix,
                                                      @Nullable ResolvedRenderState renderState) {
        if (activeHandProjectionMatrix == null || modelViewMatrix == null || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return null;
        }

        Matrix4f transform = new Matrix4f(activeHandProjectionMatrix).mul(modelViewMatrix);
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

        int padding = SCISSOR_BASE_PADDING;
        if (renderState != null) {
            padding += Mth.ceil(renderState.profile().width() * 10.0F + renderState.profile().softness() * 12.0F);
            if (renderState.profile().bloom()) {
                padding += getBloomPadding(renderState.profile());
            }
        }
        return projectedRect.expand(padding, mainTarget.width, mainTarget.height);
    }

    private static void applyScissor(@Nullable ScissorRect scissorRect) {
        if (scissorRect != null) {
            RenderSystem.enableScissor(scissorRect.x(), scissorRect.y(), scissorRect.width(), scissorRect.height());
        }
    }

    private static void clearScissor() {
        RenderSystem.disableScissor();
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

    private static void drawFullscreenQuad(ShaderInstance shader) {
        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 0.0F);
        bufferBuilder.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 0.0F);
        bufferBuilder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
        bufferBuilder.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
        RenderSystem.setShader(() -> shader);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
    }

    private static void applySamplePalette(ShaderInstance shader, HeldItemOutlineColorSampler.SampledColors sampledColors,
                                           HeldItemOutlineEffectProfile profile) {
        int paletteSize = Math.max(0, Math.min(8, sampledColors.size()));
        if (paletteSize <= 0) {
            setPaletteColor(shader, 0, new float[]{profile.red(), profile.green(), profile.blue()});
            setPaletteColor(shader, 1, new float[]{profile.secondaryRed(), profile.secondaryGreen(), profile.secondaryBlue()});
            paletteSize = 2;
        } else {
            for (int i = 0; i < 8; i++) {
                setPaletteColor(shader, i, sampledColors.color(Math.min(i, paletteSize - 1)));
            }
        }
        if (shader.getUniform("PaletteSize") != null) {
            shader.getUniform("PaletteSize").set((float) paletteSize);
        }
    }

    private static void setPaletteColor(ShaderInstance shader, int index, float[] color) {
        if (shader.getUniform("PaletteColor" + index) != null) {
            shader.getUniform("PaletteColor" + index).set(color[0], color[1], color[2], 1.0F);
        }
    }

    private static float resolveAnimationTime(Minecraft minecraft) {
        if (minecraft.level == null) {
            return 0.0F;
        }

        long wrappedGameTime = Math.floorMod(minecraft.level.getGameTime(), ANIMATION_TIME_WRAP_TICKS);
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        return (wrappedGameTime + partialTick) * 0.05F;
    }

    public record HandEffectTarget(InteractionHand hand, HeldItemOutlineEffectProfile profile,
                                   HeldItemOutlineColorSampler.SampledColors sampledColors) {
    }

    private record ResolvedRenderState(HeldItemOutlineEffectProfile profile,
                                       HeldItemOutlineColorSampler.SampledColors sampledColors) {
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
    }

    private static final class CaptureState {
        private final InteractionHand hand;
        @Nullable
        private TextureTarget outlineMaskTarget;
        @Nullable
        private RenderBuffers compatCaptureRenderBuffers;
        @Nullable
        private RenderTarget restoreTarget;
        private boolean compatCapture;
        private boolean compatPendingFlush;
        private int restoreFramebufferId;
        private int restoreViewportX;
        private int restoreViewportY;
        private int restoreViewportWidth;
        private int restoreViewportHeight;
        private boolean captureActive;
        private boolean capturedThisFrame;
        private int capturedHandCount;
        private boolean lastObservedHandEnabled = true;
        private ItemStack lastObservedStack = ItemStack.EMPTY;
        @Nullable
        private InteractionHand captureHandFilter;
        @Nullable
        private ResolvedRenderState lastEffectiveState;
        @Nullable
        private ResolvedRenderState pendingState;
        @Nullable
        private ResolvedRenderState capturedRenderState;
        @Nullable
        private Matrix4f capturedProjectionMatrix;
        @Nullable
        private Matrix4f capturedModelViewMatrix;
        @Nullable
        private ScissorRect scissorRect;
        private boolean cachedObservedHandEnabled = true;
        private ItemStack cachedObservedResolvedStack = ItemStack.EMPTY;
        @Nullable
        private HeldItemOutlineEffectProfile cachedBaseProfile;
        private long cachedRuleRevision = Long.MIN_VALUE;
        @Nullable
        private ResolvedRenderState cachedResolvedState;
        private long transitionEndMillis;
        private boolean compatFramePrepared;

        private CaptureState(InteractionHand hand) {
            this.hand = hand;
        }

        private void resetImmediateFrameState() {
            this.capturedThisFrame = false;
            this.capturedHandCount = 0;
            this.captureActive = false;
            this.restoreTarget = null;
            this.compatCapture = false;
            this.compatPendingFlush = false;
            this.restoreFramebufferId = 0;
            this.restoreViewportX = 0;
            this.restoreViewportY = 0;
            this.restoreViewportWidth = 0;
            this.restoreViewportHeight = 0;
            this.captureHandFilter = null;
            this.capturedRenderState = null;
            this.capturedProjectionMatrix = null;
            this.capturedModelViewMatrix = null;
            this.scissorRect = null;
            this.compatFramePrepared = false;
        }

        private void resetAfterComposite() {
            this.capturedThisFrame = false;
            this.capturedHandCount = 0;
            this.captureActive = false;
            this.restoreTarget = null;
            this.compatCapture = false;
            this.compatPendingFlush = false;
            this.restoreFramebufferId = 0;
            this.restoreViewportX = 0;
            this.restoreViewportY = 0;
            this.restoreViewportWidth = 0;
            this.restoreViewportHeight = 0;
            this.captureHandFilter = null;
            this.capturedRenderState = null;
            this.capturedProjectionMatrix = null;
            this.capturedModelViewMatrix = null;
            this.scissorRect = null;
            this.compatFramePrepared = false;
        }
    }

    private static final class DuplicatingBufferSource extends MultiBufferSource.BufferSource {
        private final MultiBufferSource.BufferSource delegate;

        private DuplicatingBufferSource(MultiBufferSource.BufferSource delegate) {
            super(new ByteBufferBuilder(0), Object2ObjectSortedMaps.emptyMap());
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            VertexConsumer delegateConsumer = this.delegate.getBuffer(renderType);
            MultiBufferSource.BufferSource captureBufferSource = captureBufferSourceForCurrentHand();
            if (captureBufferSource == null) {
                return delegateConsumer;
            }

            markCurrentHandCompatCaptured();
            return VertexMultiConsumer.create(delegateConsumer, captureBufferSource.getBuffer(renderType));
        }

        @Override
        public void endBatch() {
        }

        @Override
        public void endBatch(RenderType renderType) {
        }
    }
}
