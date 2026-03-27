package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import celia.itemglint.mixin.client.GameRendererAccessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
public final class HeldItemOutlineRenderer {
    private static final int DEBUG_LOG_INTERVAL = 120;
    private static final long RULE_SWITCH_DELAY_MILLIS = 200L;
    private static final float BLOOM_BLUR_PASS_RADIUS = 2.5F;
    private static final int MIN_BLOOM_TARGET_SIZE = 64;
    private static final boolean ENABLE_MASK_DEBUG_READBACK = Boolean.getBoolean("itemglint.debugHeldOutlineReadback");
    private static final boolean ENABLE_COMPAT_DEBUG_LOG = ItemGlint.COMPAT_DEBUG;
    private static final float OCULUS_HAND_DEPTH = 0.125F;
    @Nullable
    private static Object oculusHandRendererInstance;
    @Nullable
    private static Field oculusHandRendererActiveField;
    @Nullable
    private static Field oculusHandRendererRenderingSolidField;
    private static final CaptureState MAIN_HAND_STATE = new CaptureState(InteractionHand.MAIN_HAND);
    private static final CaptureState OFF_HAND_STATE = new CaptureState(InteractionHand.OFF_HAND);
    @Nullable
    private static CaptureState activeCaptureState;
    private static TextureTarget bloomBlurTargetA;
    private static TextureTarget bloomBlurTargetB;
    private static TextureTarget bloomBlurNearTarget;
    private static long lastDebugLogGameTime = Long.MIN_VALUE;
    private static long lastCompatDebugMillis = Long.MIN_VALUE;
    @Nullable
    private static RenderBuffers embeddiumCaptureRenderBuffers;

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

    public static boolean beginCapture(Minecraft minecraft, RenderTarget mainTarget, InteractionHand hand) {
        ShaderInstance shader = HeldItemOutlineShaderRegistry.getShader();
        if (shader == null || minecraft.level == null || minecraft.player == null || !shouldRenderOutline(minecraft)) {
            debugCompat(minecraft, "beginCapture skipped: shader=" + (shader != null)
                    + ", level=" + (minecraft.level != null)
                    + ", player=" + (minecraft.player != null)
                    + ", shouldRender=" + shouldRenderOutline(minecraft));
            clearAllFrameStates();
            return false;
        }

        CaptureState state = stateFor(hand);
        state.resetImmediateFrameState();
        debugCompat(minecraft, "beginCapture start: hand=" + hand + ", target=" + mainTarget.width + "x" + mainTarget.height);
        ensureOutlineMaskTarget(mainTarget, state);
        state.outlineMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        state.outlineMaskTarget.clear(Minecraft.ON_OSX);
        state.outlineMaskTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        state.restoreTarget = mainTarget;
        state.captureActive = true;
        activeCaptureState = state;
        return true;
    }

    public static boolean beginEmbeddiumCompatCapture(Minecraft minecraft, RenderTarget mainTarget, InteractionHand hand) {
        ShaderInstance shader = HeldItemOutlineShaderRegistry.getShader();
        if (shader == null || minecraft.level == null || minecraft.player == null || !shouldRenderOutline(minecraft)) {
            debugCompat(minecraft, "beginEmbeddiumCompatCapture skipped: shader=" + (shader != null)
                    + ", level=" + (minecraft.level != null)
                    + ", player=" + (minecraft.player != null)
                    + ", shouldRender=" + shouldRenderOutline(minecraft)
                    + ", queued=" + isEmbeddiumCompatFrameQueued());
            if (!isEmbeddiumCompatFrameQueued()) {
                clearAllFrameStates();
            }
            return false;
        }

        CaptureState state = stateFor(hand);
        ensureOutlineMaskTarget(mainTarget, state);
        if (!state.embeddiumCompatFramePrepared) {
            state.outlineMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            state.outlineMaskTarget.clear(Minecraft.ON_OSX);
            state.capturedThisFrame = false;
            state.capturedHandCount = 0;
            state.lastSampledCoverage = 0.0F;
            state.embeddiumCompatFramePrepared = true;
        }

        state.outlineMaskTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        state.restoreTarget = mainTarget;
        state.captureActive = true;
        state.embeddiumCompatFrameQueued = true;
        activeCaptureState = state;
        return true;
    }

    public static void endCapture() {
        CaptureState state = activeCaptureState;
        if (state == null || !state.captureActive || state.restoreTarget == null) {
            debugCompat(Minecraft.getInstance(), "endCapture skipped: activeState=" + (state != null));
            if (state != null) {
                state.captureActive = false;
                state.restoreTarget = null;
            }
            activeCaptureState = null;
            return;
        }

        state.restoreTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        state.captureActive = false;
        state.restoreTarget = null;
        activeCaptureState = null;

        Minecraft minecraft = Minecraft.getInstance();
        inspectMaskCoverage(minecraft, state);
        debugLog(minecraft, "Captured held-item mask: hand=" + state.hand + ", completed=" + state.capturedHandCount
                + ", fbo=" + state.outlineMaskTarget.width + "x" + state.outlineMaskTarget.height
                + ", sampledCoverage=" + String.format(Locale.ROOT, "%.3f", state.lastSampledCoverage)
                + ", mainHand=" + HeldItemOutlineSettings.isMainHandEnabled()
                + ", offHand=" + HeldItemOutlineSettings.isOffHandEnabled());
        debugCompat(minecraft, "endCapture done: hand=" + state.hand + ", capturedHands=" + state.capturedHandCount
                + ", capturedThisFrame=" + state.capturedThisFrame);
    }

    public static void endEmbeddiumCompatCapture() {
        CaptureState state = activeCaptureState;
        if (state == null || !state.captureActive || state.restoreTarget == null) {
            debugCompat(Minecraft.getInstance(), "endEmbeddiumCompatCapture skipped: activeState=" + (state != null));
            if (state != null) {
                state.captureActive = false;
                state.restoreTarget = null;
            }
            activeCaptureState = null;
            return;
        }

        state.restoreTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        state.captureActive = false;
        state.restoreTarget = null;
        activeCaptureState = null;
    }

    public static void finishEmbeddiumCompatFrame(Minecraft minecraft, RenderTarget mainTarget) {
        if (!isEmbeddiumCompatFrameQueued()) {
            debugCompat(minecraft, "finishEmbeddiumCompatFrame skipped: no queued frame");
            clearAllFrameStates();
            return;
        }

        LocalPlayer player = minecraft.player;
        if (player == null) {
            clearAllFrameStates();
            return;
        }

        for (HandEffectTarget target : getRenderableHands(player)) {
            CaptureState state = stateFor(target.hand());
            if (!state.embeddiumCompatFrameQueued) {
                continue;
            }
            inspectMaskCoverage(minecraft, state);
            debugLog(minecraft, "Captured Embeddium/Oculus held-item mask: hand=" + state.hand
                    + ", fbo=" + state.outlineMaskTarget.width + "x" + state.outlineMaskTarget.height
                    + ", sampledCoverage=" + String.format(Locale.ROOT, "%.3f", state.lastSampledCoverage));
            debugCompat(minecraft, "finishEmbeddiumCompatFrame composite: hand=" + state.hand
                    + ", capturedHands=" + state.capturedHandCount
                    + ", capturedThisFrame=" + state.capturedThisFrame
                    + ", mainTarget=" + mainTarget.width + "x" + mainTarget.height);
            composite(minecraft, mainTarget, target.hand(), target.profile(), target.sampledColors());
        }
    }

    public static MultiBufferSource.BufferSource getEmbeddiumCaptureBufferSource() {
        if (embeddiumCaptureRenderBuffers == null) {
            embeddiumCaptureRenderBuffers = new RenderBuffers();
        }
        return embeddiumCaptureRenderBuffers.bufferSource();
    }

    public static void renderOculusShaderpackCompatPass(Minecraft minecraft, PoseStack poseStack, float tickDelta,
                                                        Camera camera, GameRenderer gameRenderer, ItemInHandRenderer itemInHandRenderer,
                                                        LightTexture lightTexture, Matrix4f projectionMatrix, boolean solidPass) {
        if (!shouldRenderOutlinePass(minecraft)) {
            debugCompat(minecraft, "renderOculusShaderpackCompatPass skipped by shouldRenderOutlinePass solidPass=" + solidPass);
            return;
        }

        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        GameRendererAccessor accessor = (GameRendererAccessor) gameRenderer;
        if (!accessor.getRenderHand() || accessor.getPanoramicMode() || camera.isDetached()
                || !(camera.getEntity() instanceof net.minecraft.world.entity.player.Player)
                || minecraft.gameMode == null) {
            debugCompat(minecraft, "renderOculusShaderpackCompatPass skipped by Oculus hand preconditions solidPass=" + solidPass);
            return;
        }

        MultiBufferSource.BufferSource captureBufferSource = getEmbeddiumCaptureBufferSource();
        Matrix4f scaledProjection = new Matrix4f()
                .scale(1.0F, 1.0F, OCULUS_HAND_DEPTH)
                .mul(gameRenderer.getProjectionMatrix(accessor.invokeGetFov(camera, tickDelta, false)));

        int packedLight = minecraft.getEntityRenderDispatcher().getPackedLightCoords(camera.getEntity(), tickDelta);
        for (HandEffectTarget target : getRenderableHands(player)) {
            if (!beginEmbeddiumCompatCapture(minecraft, minecraft.getMainRenderTarget(), target.hand())) {
                debugCompat(minecraft, "renderOculusShaderpackCompatPass beginEmbeddiumCompatCapture returned false solidPass=" + solidPass);
                continue;
            }

            if (!setOculusHandRendererState(false, solidPass)) {
                debugCompat(minecraft, "renderOculusShaderpackCompatPass failed to toggle Oculus hand state solidPass=" + solidPass);
                endEmbeddiumCompatCapture();
                continue;
            }

            poseStack.pushPose();
            gameRenderer.resetProjectionMatrix(scaledProjection);
            PoseStack.Pose pose = poseStack.last();
            pose.pose().identity();
            pose.normal().identity();
            accessor.invokeBobHurt(poseStack, tickDelta);
            if (minecraft.options.bobView().get()) {
                accessor.invokeBobView(poseStack, tickDelta);
            }

            lightTexture.turnOnLightLayer();
            try {
                itemInHandRenderer.renderHandsWithItems(
                        tickDelta,
                        poseStack,
                        captureBufferSource,
                        player,
                        packedLight
                );
                captureBufferSource.endBatch();
            } finally {
                setOculusHandRendererState(false, false);
                endEmbeddiumCompatCapture();
                lightTexture.turnOffLightLayer();
                gameRenderer.resetProjectionMatrix(projectionMatrix);
                poseStack.popPose();
            }
        }
    }

    public static void markHandCaptured() {
        if (activeCaptureState != null && activeCaptureState.captureActive) {
            activeCaptureState.capturedThisFrame = true;
            activeCaptureState.capturedHandCount++;
        }
    }

    public static void composite(Minecraft minecraft, RenderTarget mainTarget, InteractionHand hand, HeldItemOutlineEffectProfile profile,
                                 HeldItemOutlineColorSampler.SampledColors sampledColors) {
        CaptureState state = stateFor(hand);
        ShaderInstance outlineShader = HeldItemOutlineShaderRegistry.getShader();
        if (!state.capturedThisFrame || outlineShader == null || state.outlineMaskTarget == null) {
            debugCompat(minecraft, "composite skipped: hand=" + hand + ", capturedThisFrame=" + state.capturedThisFrame
                    + ", shader=" + (outlineShader != null)
                    + ", outlineMaskTarget=" + (state.outlineMaskTarget != null));
            debugLog(minecraft, "Skipped outline composite because the mask FBO was empty.");
            state.resetAfterComposite();
            return;
        }

        debugCompat(minecraft, "composite start: hand=" + hand + ", mainTarget=" + mainTarget.width + "x" + mainTarget.height
                + ", maskTarget=" + state.outlineMaskTarget.width + "x" + state.outlineMaskTarget.height
                + ", capturedHands=" + state.capturedHandCount);
        mainTarget.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        applyOutlineUniforms(minecraft, mainTarget, outlineShader, state.outlineMaskTarget, profile, sampledColors);
        state.outlineMaskTarget.setFilterMode(9728);
        RenderSystem.defaultBlendFunc();
        drawFullscreenQuad(outlineShader);

        if (profile.bloom()) {
            compositeBloom(minecraft, mainTarget, state.outlineMaskTarget, profile, sampledColors);
        }

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        debugLog(minecraft, "Composited held-item outline using hand=" + hand + " FBO color=" + state.outlineMaskTarget.getColorTextureId()
                + ", depth=" + state.outlineMaskTarget.getDepthTextureId()
                + ", mode=alpha_only");
        debugCompat(minecraft, "composite done: hand=" + hand + ", colorTex=" + state.outlineMaskTarget.getColorTextureId()
                + ", depthTex=" + state.outlineMaskTarget.getDepthTextureId());
        state.resetAfterComposite();
    }

    public static boolean isCaptureActive() {
        return activeCaptureState != null && activeCaptureState.captureActive;
    }

    public static boolean shouldSkipHand(InteractionHand hand) {
        return activeCaptureState != null && activeCaptureState.captureActive && activeCaptureState.hand != hand;
    }

    public static boolean hasCapturedThisFrame() {
        return MAIN_HAND_STATE.capturedThisFrame || OFF_HAND_STATE.capturedThisFrame;
    }

    public static int getCapturedHandCount() {
        return MAIN_HAND_STATE.capturedHandCount + OFF_HAND_STATE.capturedHandCount;
    }

    public static boolean isEmbeddiumCompatFrameQueued() {
        return MAIN_HAND_STATE.embeddiumCompatFrameQueued || OFF_HAND_STATE.embeddiumCompatFrameQueued;
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
        HeldItemRuleManager.ResolvedMatch resolvedMatch = handEnabled && !stack.isEmpty()
                ? HeldItemRuleManager.resolveMatch(stack)
                : HeldItemRuleManager.ResolvedMatch.NO_MATCH;
        ResolvedRenderState nextState = createResolvedRenderState(stack, resolvedMatch.profile());
        long now = System.currentTimeMillis();
        if (state.transitionEndMillis != 0L && (!HeldItemOutlineSettings.isRuleSwitchDelayEnabled() || now >= state.transitionEndMillis)) {
            completePendingTransition(state);
        }

        if (state.lastObservedHandEnabled != handEnabled || !ItemStack.isSameItemSameTags(state.lastObservedStack, observedStack)) {
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
            outlineShader.getUniform("Time").set((minecraft.level.getGameTime() + minecraft.getFrameTime()) * 0.05F);
        }
    }

    private static void clearAllFrameStates() {
        debugCompat(Minecraft.getInstance(), "clearFrameState: capturedHands=" + getCapturedHandCount()
                + ", capturedThisFrame=" + hasCapturedThisFrame()
                + ", queued=" + isEmbeddiumCompatFrameQueued());
        MAIN_HAND_STATE.resetAfterComposite();
        OFF_HAND_STATE.resetAfterComposite();
        activeCaptureState = null;
    }

    private static boolean setOculusHandRendererState(boolean active, boolean renderingSolid) {
        if (!HeldItemOutlineCompat.isOculusLoaded()) {
            return false;
        }

        try {
            if (oculusHandRendererInstance == null || oculusHandRendererActiveField == null || oculusHandRendererRenderingSolidField == null) {
                Class<?> handRendererClass = Class.forName("net.irisshaders.iris.pathways.HandRenderer");
                Field instanceField = handRendererClass.getField("INSTANCE");
                Field activeField = handRendererClass.getDeclaredField("ACTIVE");
                Field renderingSolidField = handRendererClass.getDeclaredField("renderingSolid");
                activeField.setAccessible(true);
                renderingSolidField.setAccessible(true);
                oculusHandRendererInstance = instanceField.get(null);
                oculusHandRendererActiveField = activeField;
                oculusHandRendererRenderingSolidField = renderingSolidField;
            }

            oculusHandRendererActiveField.setBoolean(oculusHandRendererInstance, active);
            oculusHandRendererRenderingSolidField.setBoolean(oculusHandRendererInstance, renderingSolid);
            return true;
        } catch (ReflectiveOperationException exception) {
            debugCompat(Minecraft.getInstance(), "Failed to toggle Oculus HandRenderer state: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    public static void debugCompat(@Nullable Minecraft minecraft, String message) {
        if (!ENABLE_COMPAT_DEBUG_LOG) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastCompatDebugMillis != Long.MIN_VALUE && now - lastCompatDebugMillis < 2000L) {
            return;
        }
        lastCompatDebugMillis = now;

        if (minecraft != null && minecraft.level != null) {
            String formatted = "[HeldItemOutlineCompat][gameTime=" + minecraft.level.getGameTime() + "] " + message;
            ItemGlint.LOGGER.warn(formatted);
            System.err.println(formatted);
        } else {
            String formatted = "[HeldItemOutlineCompat] " + message;
            ItemGlint.LOGGER.warn(formatted);
            System.err.println(formatted);
        }
    }

    private static void inspectMaskCoverage(Minecraft minecraft, CaptureState state) {
        if (!ENABLE_MASK_DEBUG_READBACK || !ItemGlint.LOGGER.isDebugEnabled() || state.outlineMaskTarget == null || minecraft.level == null) {
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        if (gameTime % DEBUG_LOG_INTERVAL != 0L) {
            return;
        }

        state.outlineMaskTarget.bindRead();
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, state.outlineMaskTarget.width, state.outlineMaskTarget.height, false)) {
            image.downloadTexture(0, false);
            int sampleX = 24;
            int sampleY = 14;
            int active = 0;
            int total = sampleX * sampleY;
            int centerAlpha = sampleAlpha(image, image.getWidth() / 2, image.getHeight() / 2);
            int topLeftAlpha = sampleAlpha(image, 0, 0);
            int topRightAlpha = sampleAlpha(image, image.getWidth() - 1, 0);
            int bottomLeftAlpha = sampleAlpha(image, 0, image.getHeight() - 1);
            int bottomRightAlpha = sampleAlpha(image, image.getWidth() - 1, image.getHeight() - 1);
            int minX = sampleX;
            int minY = sampleY;
            int maxX = -1;
            int maxY = -1;

            for (int y = 0; y < sampleY; y++) {
                int pixelY = Math.min(image.getHeight() - 1, Math.max(0, Math.round((y + 0.5F) * image.getHeight() / sampleY)));
                for (int x = 0; x < sampleX; x++) {
                    int pixelX = Math.min(image.getWidth() - 1, Math.max(0, Math.round((x + 0.5F) * image.getWidth() / sampleX)));
                    if (sampleAlpha(image, pixelX, pixelY) > 8) {
                        active++;
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            state.lastSampledCoverage = total > 0 ? active / (float) total : 0.0F;
            ItemGlint.LOGGER.debug("[HeldItemOutline] Hand={} mask sample coverage={} active={}/{} centerAlpha={} corners=[{},{},{},{}] bounds={}..{},{}..{}",
                    state.hand,
                    String.format(Locale.ROOT, "%.3f", state.lastSampledCoverage),
                    active, total, centerAlpha, topLeftAlpha, topRightAlpha, bottomLeftAlpha, bottomRightAlpha,
                    minX, maxX, minY, maxY);
        } catch (Exception exception) {
            ItemGlint.LOGGER.warn("[HeldItemOutline] Failed to inspect outline mask texture", exception);
        } finally {
            state.outlineMaskTarget.unbindRead();
        }
    }

    private static int sampleAlpha(NativeImage image, int x, int y) {
        int rgba = image.getPixelRGBA(x, y);
        return rgba >>> 24 & 0xFF;
    }

    private static void debugLog(Minecraft minecraft, String message) {
        if (minecraft.level == null) {
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        if (gameTime == lastDebugLogGameTime || gameTime % DEBUG_LOG_INTERVAL != 0L) {
            return;
        }
        lastDebugLogGameTime = gameTime;
        ItemGlint.LOGGER.debug("[HeldItemOutline] {}", message);
    }

    private static void compositeBloom(Minecraft minecraft, RenderTarget mainTarget, TextureTarget outlineMaskTarget,
                                       HeldItemOutlineEffectProfile profile, HeldItemOutlineColorSampler.SampledColors sampledColors) {
        ShaderInstance blurShader = HeldItemBloomBlurShaderRegistry.getShader();
        ShaderInstance bloomShader = HeldItemBloomShaderRegistry.getShader();
        if (blurShader == null || bloomShader == null) {
            return;
        }

        ensureBloomTargets(mainTarget, profile);
        RenderSystem.disableBlend();
        if (blurShader.getUniform("ScreenSize") != null) {
            blurShader.getUniform("ScreenSize").set((float) bloomBlurTargetA.width, (float) bloomBlurTargetA.height);
        }

        float nearBlurRadius = Math.max(1.0F, 1.2F + profile.width() * 0.35F);
        float farBlurRadius = Math.max(1.0F, profile.bloomRadius() * 3.25F);
        int farBlurPasses = Mth.clamp((int) Math.ceil(farBlurRadius / BLOOM_BLUR_PASS_RADIUS),
                HeldItemOutlineSettings.MIN_BLOOM_MAX_PASSES,
                profile.bloomMaxPasses());
        float farPassRadius = farBlurRadius / farBlurPasses;

        int nearTexture = applyBlurChain(blurShader,
                outlineMaskTarget.getColorTextureId(),
                bloomBlurTargetA,
                bloomBlurNearTarget,
                nearBlurRadius,
                1);
        int farTexture = applyBlurChain(blurShader,
                outlineMaskTarget.getColorTextureId(),
                bloomBlurTargetA,
                bloomBlurTargetB,
                farPassRadius,
                farBlurPasses);

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
            bloomShader.getUniform("Time").set((minecraft.level.getGameTime() + minecraft.getFrameTime()) * 0.05F);
        }
        if (bloomShader.getUniform("BloomStrength") != null) {
            bloomShader.getUniform("BloomStrength").set(profile.bloomStrength());
        }
        if (bloomShader.getUniform("BloomRadius") != null) {
            bloomShader.getUniform("BloomRadius").set(profile.bloomRadius());
        }
        drawFullscreenQuad(bloomShader);
    }

    private static int getBloomTargetDimension(int fullSize, HeldItemOutlineEffectProfile profile) {
        int downsampleFactor = profile.bloomResolution().downsampleFactor();
        return Math.max(MIN_BLOOM_TARGET_SIZE, Math.max(1, fullSize / Math.max(1, downsampleFactor)));
    }

    private static int applyBlurChain(ShaderInstance blurShader, int sourceTexture, TextureTarget tempTarget,
                                      TextureTarget destinationTarget, float blurRadius, int passes) {
        tempTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        tempTarget.clear(Minecraft.ON_OSX);
        destinationTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        destinationTarget.clear(Minecraft.ON_OSX);

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
            drawFullscreenQuad(blurShader);

            destinationTarget.bindWrite(true);
            blurShader.setSampler("DiffuseSampler", tempTarget.getColorTextureId());
            if (blurShader.getUniform("BlurDirection") != null) {
                blurShader.getUniform("BlurDirection").set(0.0F, 1.0F);
            }
            drawFullscreenQuad(blurShader);

            currentTexture = destinationTarget.getColorTextureId();
        }
        return currentTexture;
    }

    private static void drawFullscreenQuad(ShaderInstance shader) {
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(-1.0D, -1.0D, 0.0D).uv(0.0F, 0.0F).endVertex();
        bufferBuilder.vertex(1.0D, -1.0D, 0.0D).uv(1.0F, 0.0F).endVertex();
        bufferBuilder.vertex(1.0D, 1.0D, 0.0D).uv(1.0F, 1.0F).endVertex();
        bufferBuilder.vertex(-1.0D, 1.0D, 0.0D).uv(0.0F, 1.0F).endVertex();
        RenderSystem.setShader(() -> shader);
        BufferUploader.drawWithShader(bufferBuilder.end());
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

    public record HandEffectTarget(InteractionHand hand, HeldItemOutlineEffectProfile profile,
                                   HeldItemOutlineColorSampler.SampledColors sampledColors) {
    }

    private record ResolvedRenderState(HeldItemOutlineEffectProfile profile,
                                       HeldItemOutlineColorSampler.SampledColors sampledColors) {
    }

    private static final class CaptureState {
        private final InteractionHand hand;
        @Nullable
        private TextureTarget outlineMaskTarget;
        @Nullable
        private RenderTarget restoreTarget;
        private boolean captureActive;
        private boolean capturedThisFrame;
        private int capturedHandCount;
        private float lastSampledCoverage;
        private boolean embeddiumCompatFramePrepared;
        private boolean embeddiumCompatFrameQueued;
        private boolean lastObservedHandEnabled = true;
        private ItemStack lastObservedStack = ItemStack.EMPTY;
        @Nullable
        private ResolvedRenderState lastEffectiveState;
        @Nullable
        private ResolvedRenderState pendingState;
        private long transitionEndMillis;

        private CaptureState(InteractionHand hand) {
            this.hand = hand;
        }

        private void resetImmediateFrameState() {
            this.capturedThisFrame = false;
            this.capturedHandCount = 0;
            this.lastSampledCoverage = 0.0F;
            this.captureActive = false;
            this.restoreTarget = null;
            this.embeddiumCompatFramePrepared = false;
            this.embeddiumCompatFrameQueued = false;
        }

        private void resetAfterComposite() {
            this.capturedThisFrame = false;
            this.capturedHandCount = 0;
            this.lastSampledCoverage = 0.0F;
            this.captureActive = false;
            this.restoreTarget = null;
            this.embeddiumCompatFramePrepared = false;
            this.embeddiumCompatFrameQueued = false;
        }

        private void resetAll() {
            resetAfterComposite();
            this.lastObservedHandEnabled = true;
            this.lastObservedStack = ItemStack.EMPTY;
            this.lastEffectiveState = null;
            this.pendingState = null;
            this.transitionEndMillis = 0L;
        }
    }
}
