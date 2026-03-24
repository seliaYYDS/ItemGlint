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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import celia.itemglint.mixin.client.GameRendererAccessor;

import java.lang.reflect.Field;

public final class HeldItemOutlineRenderer {
    private static final int DEBUG_LOG_INTERVAL = 120;
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

    private static TextureTarget outlineMaskTarget;
    private static TextureTarget bloomBlurTargetA;
    private static TextureTarget bloomBlurTargetB;
    private static TextureTarget bloomBlurNearTarget;
    @Nullable
    private static RenderTarget restoreTarget;
    private static boolean captureActive;
    private static boolean capturedThisFrame;
    private static int capturedHandCount;
    private static float lastSampledCoverage;
    private static long lastDebugLogGameTime = Long.MIN_VALUE;
    private static long lastCompatDebugMillis = Long.MIN_VALUE;
    private static boolean embeddiumCompatFramePrepared;
    private static boolean embeddiumCompatFrameQueued;
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
                && hasRenderableItem(player);
    }

    public static boolean beginCapture(Minecraft minecraft, RenderTarget mainTarget) {
        ShaderInstance shader = HeldItemOutlineShaderRegistry.getShader();
        if (shader == null || minecraft.level == null || minecraft.player == null || !shouldRenderOutline(minecraft)) {
            debugCompat(minecraft, "beginCapture skipped: shader=" + (shader != null)
                    + ", level=" + (minecraft.level != null)
                    + ", player=" + (minecraft.player != null)
                    + ", shouldRender=" + shouldRenderOutline(minecraft));
            clearFrameState();
            return false;
        }

        debugCompat(minecraft, "beginCapture start: target=" + mainTarget.width + "x" + mainTarget.height);
        ensureOutlineMaskTarget(mainTarget);
        outlineMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        outlineMaskTarget.clear(Minecraft.ON_OSX);
        outlineMaskTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        restoreTarget = mainTarget;
        captureActive = true;
        capturedThisFrame = false;
        capturedHandCount = 0;
        return true;
    }

    public static boolean beginEmbeddiumCompatCapture(Minecraft minecraft, RenderTarget mainTarget) {
        ShaderInstance shader = HeldItemOutlineShaderRegistry.getShader();
        if (shader == null || minecraft.level == null || minecraft.player == null || !shouldRenderOutline(minecraft)) {
            debugCompat(minecraft, "beginEmbeddiumCompatCapture skipped: shader=" + (shader != null)
                    + ", level=" + (minecraft.level != null)
                    + ", player=" + (minecraft.player != null)
                    + ", shouldRender=" + shouldRenderOutline(minecraft)
                    + ", queued=" + embeddiumCompatFrameQueued);
            if (!embeddiumCompatFrameQueued) {
                clearFrameState();
            }
            return false;
        }

        ensureOutlineMaskTarget(mainTarget);
        if (!embeddiumCompatFramePrepared) {
            outlineMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            outlineMaskTarget.clear(Minecraft.ON_OSX);
            capturedThisFrame = false;
            capturedHandCount = 0;
            lastSampledCoverage = 0.0F;
            embeddiumCompatFramePrepared = true;
        }

        outlineMaskTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        restoreTarget = mainTarget;
        captureActive = true;
        embeddiumCompatFrameQueued = true;
        return true;
    }

    public static void endCapture() {
        if (!captureActive || restoreTarget == null) {
            debugCompat(Minecraft.getInstance(), "endCapture skipped: active=" + captureActive + ", restoreTarget=" + (restoreTarget != null));
            clearFrameState();
            return;
        }

        restoreTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        captureActive = false;

        Minecraft minecraft = Minecraft.getInstance();
        inspectMaskCoverage(minecraft);
        debugLog(minecraft, "Captured held-item mask: completed=" + capturedHandCount
                + ", fbo=" + outlineMaskTarget.width + "x" + outlineMaskTarget.height
                + ", sampledCoverage=" + String.format(java.util.Locale.ROOT, "%.3f", lastSampledCoverage)
                + ", mainHand=" + HeldItemOutlineSettings.isMainHandEnabled()
                + ", offHand=" + HeldItemOutlineSettings.isOffHandEnabled());
        debugCompat(minecraft, "endCapture done: capturedHands=" + capturedHandCount
                + ", capturedThisFrame=" + capturedThisFrame);
    }

    public static void endEmbeddiumCompatCapture() {
        if (!captureActive || restoreTarget == null) {
            debugCompat(Minecraft.getInstance(), "endEmbeddiumCompatCapture skipped: active=" + captureActive
                    + ", restoreTarget=" + (restoreTarget != null));
            captureActive = false;
            restoreTarget = null;
            return;
        }

        restoreTarget.bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        captureActive = false;
        restoreTarget = null;
    }

    public static void finishEmbeddiumCompatFrame(Minecraft minecraft, RenderTarget mainTarget) {
        if (!embeddiumCompatFrameQueued) {
            debugCompat(minecraft, "finishEmbeddiumCompatFrame skipped: no queued frame");
            clearFrameState();
            return;
        }

        inspectMaskCoverage(minecraft);
        debugLog(minecraft, "Captured Embeddium/Oculus held-item mask: completed=" + capturedHandCount
                + ", fbo=" + outlineMaskTarget.width + "x" + outlineMaskTarget.height
                + ", sampledCoverage=" + String.format(java.util.Locale.ROOT, "%.3f", lastSampledCoverage)
                + ", mainHand=" + HeldItemOutlineSettings.isMainHandEnabled()
                + ", offHand=" + HeldItemOutlineSettings.isOffHandEnabled());
        debugCompat(minecraft, "finishEmbeddiumCompatFrame composite: capturedHands=" + capturedHandCount
                + ", capturedThisFrame=" + capturedThisFrame
                + ", mainTarget=" + mainTarget.width + "x" + mainTarget.height);
        composite(minecraft, mainTarget);
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

        GameRendererAccessor accessor = (GameRendererAccessor) gameRenderer;
        if (!accessor.getRenderHand() || accessor.getPanoramicMode() || camera.isDetached()
                || !(camera.getEntity() instanceof net.minecraft.world.entity.player.Player)
                || minecraft.gameMode == null) {
            debugCompat(minecraft, "renderOculusShaderpackCompatPass skipped by Oculus hand preconditions solidPass=" + solidPass);
            return;
        }

        if (!beginEmbeddiumCompatCapture(minecraft, minecraft.getMainRenderTarget())) {
            debugCompat(minecraft, "renderOculusShaderpackCompatPass beginEmbeddiumCompatCapture returned false solidPass=" + solidPass);
            return;
        }

        MultiBufferSource.BufferSource captureBufferSource = getEmbeddiumCaptureBufferSource();
        Matrix4f scaledProjection = new Matrix4f()
                .scale(1.0F, 1.0F, OCULUS_HAND_DEPTH)
                .mul(gameRenderer.getProjectionMatrix(accessor.invokeGetFov(camera, tickDelta, false)));

        if (!setOculusHandRendererState(false, solidPass)) {
            debugCompat(minecraft, "renderOculusShaderpackCompatPass failed to toggle Oculus hand state solidPass=" + solidPass);
            endEmbeddiumCompatCapture();
            return;
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

        int packedLight = minecraft.getEntityRenderDispatcher().getPackedLightCoords(camera.getEntity(), tickDelta);
        lightTexture.turnOnLightLayer();
        try {
            itemInHandRenderer.renderHandsWithItems(
                    tickDelta,
                    poseStack,
                    captureBufferSource,
                    minecraft.player,
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

    public static void markHandCaptured() {
        if (captureActive) {
            capturedThisFrame = true;
            capturedHandCount++;
        }
    }

    public static void composite(Minecraft minecraft, RenderTarget mainTarget) {
        ShaderInstance outlineShader = HeldItemOutlineShaderRegistry.getShader();
        if (!capturedThisFrame || outlineShader == null || outlineMaskTarget == null) {
            debugCompat(minecraft, "composite skipped: capturedThisFrame=" + capturedThisFrame
                    + ", shader=" + (outlineShader != null)
                    + ", outlineMaskTarget=" + (outlineMaskTarget != null));
            debugLog(minecraft, "Skipped outline composite because the mask FBO was empty.");
            clearFrameState();
            return;
        }

        debugCompat(minecraft, "composite start: mainTarget=" + mainTarget.width + "x" + mainTarget.height
                + ", maskTarget=" + outlineMaskTarget.width + "x" + outlineMaskTarget.height
                + ", capturedHands=" + capturedHandCount);
        mainTarget.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        outlineShader.setSampler("DiffuseSampler", outlineMaskTarget.getColorTextureId());
        outlineShader.setSampler("DepthSampler", outlineMaskTarget.getDepthTextureId());
        if (outlineShader.getUniform("ScreenSize") != null) {
            outlineShader.getUniform("ScreenSize").set((float) mainTarget.width, (float) mainTarget.height);
        }
        if (outlineShader.getUniform("OutlineColor") != null) {
            outlineShader.getUniform("OutlineColor").set(
                    HeldItemOutlineSettings.getRed(),
                    HeldItemOutlineSettings.getGreen(),
                    HeldItemOutlineSettings.getBlue(),
                    1.0F
            );
        }
        if (outlineShader.getUniform("SecondaryColor") != null) {
            outlineShader.getUniform("SecondaryColor").set(
                    HeldItemOutlineSettings.getSecondaryRed(),
                    HeldItemOutlineSettings.getSecondaryGreen(),
                    HeldItemOutlineSettings.getSecondaryBlue(),
                    1.0F
            );
        }
        if (outlineShader.getUniform("ColorMode") != null) {
            outlineShader.getUniform("ColorMode").set(HeldItemOutlineSettings.getColorMode().shaderValue());
        }
        if (outlineShader.getUniform("ColorScrollSpeed") != null) {
            outlineShader.getUniform("ColorScrollSpeed").set(HeldItemOutlineSettings.getColorScrollSpeed());
        }
        if (outlineShader.getUniform("OutlineWidth") != null) {
            outlineShader.getUniform("OutlineWidth").set(HeldItemOutlineSettings.getWidth());
        }
        if (outlineShader.getUniform("Softness") != null) {
            outlineShader.getUniform("Softness").set(HeldItemOutlineSettings.getSoftness());
        }
        if (outlineShader.getUniform("AlphaThreshold") != null) {
            outlineShader.getUniform("AlphaThreshold").set(HeldItemOutlineSettings.getAlphaThreshold());
        }
        if (outlineShader.getUniform("Opacity") != null) {
            outlineShader.getUniform("Opacity").set(HeldItemOutlineSettings.getOpacity());
        }
        if (outlineShader.getUniform("DepthWeight") != null) {
            outlineShader.getUniform("DepthWeight").set(HeldItemOutlineSettings.getDepthWeight());
        }
        if (outlineShader.getUniform("GlowStrength") != null) {
            outlineShader.getUniform("GlowStrength").set(HeldItemOutlineSettings.getGlowStrength());
        }
        if (outlineShader.getUniform("Time") != null) {
            outlineShader.getUniform("Time").set((minecraft.level.getGameTime() + minecraft.getFrameTime()) * 0.05F);
        }
        if (outlineShader.getUniform("PulseSpeed") != null) {
            outlineShader.getUniform("PulseSpeed").set(HeldItemOutlineSettings.getPulseSpeed());
        }
        if (outlineShader.getUniform("PulseAmount") != null) {
            outlineShader.getUniform("PulseAmount").set(HeldItemOutlineSettings.getPulseAmount());
        }
        outlineMaskTarget.setFilterMode(9728);
        RenderSystem.defaultBlendFunc();
        drawFullscreenQuad(outlineShader);

        if (HeldItemOutlineSettings.isBloomEnabled()) {
            compositeBloom(minecraft, mainTarget);
        }

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        debugLog(minecraft, "Composited held-item outline using FBO color=" + outlineMaskTarget.getColorTextureId()
                + ", depth=" + outlineMaskTarget.getDepthTextureId()
                + ", mode=alpha_only");
        debugCompat(minecraft, "composite done: colorTex=" + outlineMaskTarget.getColorTextureId()
                + ", depthTex=" + outlineMaskTarget.getDepthTextureId());
        clearFrameState();
    }

    public static boolean isCaptureActive() {
        return captureActive;
    }

    public static boolean shouldSkipHand(InteractionHand hand) {
        return captureActive && !HeldItemOutlineSettings.shouldRenderHand(hand);
    }

    public static boolean hasCapturedThisFrame() {
        return capturedThisFrame;
    }

    public static int getCapturedHandCount() {
        return capturedHandCount;
    }

    public static boolean isEmbeddiumCompatFrameQueued() {
        return embeddiumCompatFrameQueued;
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

    private static boolean hasRenderableItem(LocalPlayer player) {
        return HeldItemOutlineSettings.isMainHandEnabled() && !player.getMainHandItem().isEmpty()
                || HeldItemOutlineSettings.isOffHandEnabled() && !player.getOffhandItem().isEmpty();
    }

    private static void ensureOutlineMaskTarget(RenderTarget mainTarget) {
        if (outlineMaskTarget == null) {
            outlineMaskTarget = new TextureTarget(mainTarget.width, mainTarget.height, true, Minecraft.ON_OSX);
            outlineMaskTarget.setFilterMode(9729);
        } else if (outlineMaskTarget.width != mainTarget.width || outlineMaskTarget.height != mainTarget.height) {
            outlineMaskTarget.resize(mainTarget.width, mainTarget.height, Minecraft.ON_OSX);
            outlineMaskTarget.setFilterMode(9729);
        }
    }

    private static void ensureBloomTargets(RenderTarget mainTarget) {
        int bloomWidth = getBloomTargetDimension(mainTarget.width);
        int bloomHeight = getBloomTargetDimension(mainTarget.height);

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

    private static void clearFrameState() {
        debugCompat(Minecraft.getInstance(), "clearFrameState: capturedHands=" + capturedHandCount
                + ", capturedThisFrame=" + capturedThisFrame
                + ", queued=" + embeddiumCompatFrameQueued);
        captureActive = false;
        capturedThisFrame = false;
        capturedHandCount = 0;
        lastSampledCoverage = 0.0F;
        restoreTarget = null;
        embeddiumCompatFramePrepared = false;
        embeddiumCompatFrameQueued = false;
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

    private static void inspectMaskCoverage(Minecraft minecraft) {
        if (!ENABLE_MASK_DEBUG_READBACK || !ItemGlint.LOGGER.isDebugEnabled() || outlineMaskTarget == null || minecraft.level == null) {
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        if (gameTime % DEBUG_LOG_INTERVAL != 0L) {
            return;
        }

        outlineMaskTarget.bindRead();
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, outlineMaskTarget.width, outlineMaskTarget.height, false)) {
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
            lastSampledCoverage = total > 0 ? active / (float) total : 0.0F;
            ItemGlint.LOGGER.debug("[HeldItemOutline] Mask sample coverage={} active={}/{} centerAlpha={} corners=[{},{},{},{}] bounds={}..{},{}..{}",
                    String.format(java.util.Locale.ROOT, "%.3f", lastSampledCoverage),
                    active, total, centerAlpha, topLeftAlpha, topRightAlpha, bottomLeftAlpha, bottomRightAlpha,
                    minX, maxX, minY, maxY);
        } catch (Exception exception) {
            ItemGlint.LOGGER.warn("[HeldItemOutline] Failed to inspect outline mask texture", exception);
        } finally {
            outlineMaskTarget.unbindRead();
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

    private static void compositeBloom(Minecraft minecraft, RenderTarget mainTarget) {
        ShaderInstance blurShader = HeldItemBloomBlurShaderRegistry.getShader();
        ShaderInstance bloomShader = HeldItemBloomShaderRegistry.getShader();
        if (blurShader == null || bloomShader == null || outlineMaskTarget == null) {
            return;
        }

        ensureBloomTargets(mainTarget);
        RenderSystem.disableBlend();
        if (blurShader.getUniform("ScreenSize") != null) {
            blurShader.getUniform("ScreenSize").set((float) bloomBlurTargetA.width, (float) bloomBlurTargetA.height);
        }

        float nearBlurRadius = Math.max(1.0F, 1.2F + HeldItemOutlineSettings.getWidth() * 0.35F);
        float farBlurRadius = Math.max(1.0F, HeldItemOutlineSettings.getBloomRadius() * 3.25F);
        int farBlurPasses = Mth.clamp((int) Math.ceil(farBlurRadius / BLOOM_BLUR_PASS_RADIUS),
                HeldItemOutlineSettings.MIN_BLOOM_MAX_PASSES,
                HeldItemOutlineSettings.getBloomMaxPasses());
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
            bloomShader.getUniform("OutlineColor").set(
                    HeldItemOutlineSettings.getRed(),
                    HeldItemOutlineSettings.getGreen(),
                    HeldItemOutlineSettings.getBlue(),
                    1.0F
            );
        }
        if (bloomShader.getUniform("SecondaryColor") != null) {
            bloomShader.getUniform("SecondaryColor").set(
                    HeldItemOutlineSettings.getSecondaryRed(),
                    HeldItemOutlineSettings.getSecondaryGreen(),
                    HeldItemOutlineSettings.getSecondaryBlue(),
                    1.0F
            );
        }
        if (bloomShader.getUniform("ColorMode") != null) {
            bloomShader.getUniform("ColorMode").set(HeldItemOutlineSettings.getColorMode().shaderValue());
        }
        if (bloomShader.getUniform("ColorScrollSpeed") != null) {
            bloomShader.getUniform("ColorScrollSpeed").set(HeldItemOutlineSettings.getColorScrollSpeed());
        }
        if (bloomShader.getUniform("AlphaThreshold") != null) {
            bloomShader.getUniform("AlphaThreshold").set(HeldItemOutlineSettings.getAlphaThreshold());
        }
        if (bloomShader.getUniform("GlowStrength") != null) {
            bloomShader.getUniform("GlowStrength").set(HeldItemOutlineSettings.getGlowStrength());
        }
        if (bloomShader.getUniform("Time") != null) {
            bloomShader.getUniform("Time").set((minecraft.level.getGameTime() + minecraft.getFrameTime()) * 0.05F);
        }
        if (bloomShader.getUniform("BloomStrength") != null) {
            bloomShader.getUniform("BloomStrength").set(HeldItemOutlineSettings.getBloomStrength());
        }
        if (bloomShader.getUniform("BloomRadius") != null) {
            bloomShader.getUniform("BloomRadius").set(HeldItemOutlineSettings.getBloomRadius());
        }
        drawFullscreenQuad(bloomShader);
    }

    private static int getBloomTargetDimension(int fullSize) {
        int downsampleFactor = HeldItemOutlineSettings.getBloomResolution().downsampleFactor();
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
}
