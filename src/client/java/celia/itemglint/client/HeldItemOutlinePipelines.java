package celia.itemglint.client;

import celia.itemglint.ItemGlint;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class HeldItemOutlinePipelines {
    private static boolean initialized;

    private static final Identifier OUTLINE_PIPELINE_ID = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, "pipeline/held_item_outline");
    private static final Identifier BLOOM_BLUR_PIPELINE_ID = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, "pipeline/held_item_bloom_blur");
    private static final Identifier BLOOM_PIPELINE_ID = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, "pipeline/held_item_bloom");
    private static final Identifier SCREEN_QUAD_SHADER = Identifier.fromNamespaceAndPath("minecraft", "core/screenquad");
    private static final Identifier OUTLINE_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, "core/held_item_outline");
    private static final Identifier BLOOM_BLUR_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, "core/held_item_bloom_blur");
    private static final Identifier BLOOM_FRAGMENT_SHADER = Identifier.fromNamespaceAndPath(ItemGlint.MOD_ID, "core/held_item_bloom");

    private static final RenderPipeline HELD_ITEM_OUTLINE = RenderPipelines.register(
            RenderPipeline.builder()
                    .withLocation(OUTLINE_PIPELINE_ID)
                    .withVertexShader(SCREEN_QUAD_SHADER)
                    .withFragmentShader(OUTLINE_FRAGMENT_SHADER)
                    .withSampler("DiffuseSampler")
                    .withSampler("DepthSampler")
                    .withUniform("OutlineInfo", UniformType.UNIFORM_BUFFER)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build()
    );

    private static final RenderPipeline HELD_ITEM_BLOOM_BLUR = RenderPipelines.register(
            RenderPipeline.builder()
                    .withLocation(BLOOM_BLUR_PIPELINE_ID)
                    .withVertexShader(SCREEN_QUAD_SHADER)
                    .withFragmentShader(BLOOM_BLUR_FRAGMENT_SHADER)
                    .withSampler("DiffuseSampler")
                    .withUniform("BlurInfo", UniformType.UNIFORM_BUFFER)
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build()
    );

    private static final RenderPipeline HELD_ITEM_BLOOM = RenderPipelines.register(
            RenderPipeline.builder()
                    .withLocation(BLOOM_PIPELINE_ID)
                    .withVertexShader(SCREEN_QUAD_SHADER)
                    .withFragmentShader(BLOOM_FRAGMENT_SHADER)
                    .withSampler("DiffuseSampler")
                    .withSampler("NearBlurSampler")
                    .withSampler("FarBlurSampler")
                    .withUniform("OutlineInfo", UniformType.UNIFORM_BUFFER)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build()
    );

    private HeldItemOutlinePipelines() {
    }

    public static void initialize() {
        initialized = true;
    }

    public static boolean isLoaded() {
        return initialized;
    }

    public static RenderPipeline outline() {
        return HELD_ITEM_OUTLINE;
    }

    public static RenderPipeline bloomBlur() {
        return HELD_ITEM_BLOOM_BLUR;
    }

    public static RenderPipeline bloom() {
        return HELD_ITEM_BLOOM;
    }
}
