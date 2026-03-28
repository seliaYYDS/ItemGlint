#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform vec2 ScreenSize;
uniform vec4 OutlineColor;
uniform vec4 SecondaryColor;
uniform float PaletteSize;
uniform vec4 PaletteColor0;
uniform vec4 PaletteColor1;
uniform vec4 PaletteColor2;
uniform vec4 PaletteColor3;
uniform vec4 PaletteColor4;
uniform vec4 PaletteColor5;
uniform vec4 PaletteColor6;
uniform vec4 PaletteColor7;
uniform float OutlineWidth;
uniform float Softness;
uniform float AlphaThreshold;
uniform float Opacity;
uniform float DepthWeight;
uniform float GlowStrength;
uniform float ColorMode;
uniform float ColorScrollSpeed;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

const int OUTLINE_SAMPLE_COUNT = 16;
const vec2 OUTLINE_DIRECTIONS[OUTLINE_SAMPLE_COUNT] = vec2[](
    vec2(1.0, 0.0),
    vec2(0.9238795, 0.3826834),
    vec2(0.7071068, 0.7071068),
    vec2(0.3826834, 0.9238795),
    vec2(0.0, 1.0),
    vec2(-0.3826834, 0.9238795),
    vec2(-0.7071068, 0.7071068),
    vec2(-0.9238795, 0.3826834),
    vec2(-1.0, 0.0),
    vec2(-0.9238795, -0.3826834),
    vec2(-0.7071068, -0.7071068),
    vec2(-0.3826834, -0.9238795),
    vec2(0.0, -1.0),
    vec2(0.3826834, -0.9238795),
    vec2(0.7071068, -0.7071068),
    vec2(0.9238795, -0.3826834)
);

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

float maskAlpha(vec2 uv) {
    return texture(DiffuseSampler, clamp(uv, vec2(0.0), vec2(1.0))).a;
}

float depthAt(vec2 uv) {
    return texture(DepthSampler, clamp(uv, vec2(0.0), vec2(1.0))).r;
}

float maskWeight(float alpha) {
    float feather = 0.02 + Softness * 0.03;
    return smoothstep(AlphaThreshold - feather, AlphaThreshold + feather, alpha);
}

vec3 hsv2rgb(vec3 c) {
    vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    rgb = rgb * rgb * (3.0 - 2.0 * rgb);
    return c.z * mix(vec3(1.0), rgb, c.y);
}

vec3 paletteColor(float index) {
    if (index < 0.5) return PaletteColor0.rgb;
    if (index < 1.5) return PaletteColor1.rgb;
    if (index < 2.5) return PaletteColor2.rgb;
    if (index < 3.5) return PaletteColor3.rgb;
    if (index < 4.5) return PaletteColor4.rgb;
    if (index < 5.5) return PaletteColor5.rgb;
    if (index < 6.5) return PaletteColor6.rgb;
    return PaletteColor7.rgb;
}

vec3 sampledScrollColor(vec2 uv) {
    float size = max(PaletteSize, 1.0);
    if (size < 1.5) {
        return PaletteColor0.rgb;
    }
    float flow = fract(uv.x * 1.6 + uv.y * 1.1 - Time * 0.12 * ColorScrollSpeed);
    float scaled = flow * size;
    float idx0 = floor(scaled);
    float idx1 = mod(idx0 + 1.0, size);
    float blend = fract(scaled);
    return mix(paletteColor(idx0), paletteColor(idx1), blend);
}

vec3 outlineBaseColor(vec2 uv) {
    if (ColorMode < 0.5) {
        return OutlineColor.rgb;
    }

    float flow = uv.x * 18.0 + uv.y * 12.0 - Time * 4.0 * ColorScrollSpeed;
    if (ColorMode < 1.5) {
        float dualMix = 0.5 + 0.5 * sin(flow);
        return mix(OutlineColor.rgb, SecondaryColor.rgb, dualMix);
    }
    if (ColorMode < 2.5) {
        float hue = fract(uv.x * 0.22 + uv.y * 0.14 - Time * 0.08 * ColorScrollSpeed);
        return hsv2rgb(vec3(hue, 0.85, 1.0));
    }
    return sampledScrollColor(uv);
}

void main() {
    vec2 texel = 1.0 / max(ScreenSize, vec2(1.0));
    float radius = max(OutlineWidth, 0.5);
    float softnessNorm = saturate((Softness - 0.10) / 3.90);
    float featherRadius = mix(0.45, 2.8, softnessNorm);
    float bridgeRadius = max(radius * 0.62, radius - 0.85);

    float centerWeight = maskWeight(maskAlpha(texCoord));
    float outerCore = 0.0;
    float outerFeather = 0.0;
    float bridgeCore = 0.0;
    float coreSum = 0.0;
    float featherSum = 0.0;
    float nearestDepth = depthAt(texCoord);

    for (int i = 0; i < OUTLINE_SAMPLE_COUNT; i++) {
        vec2 direction = OUTLINE_DIRECTIONS[i] * texel;
        vec2 coreUv = texCoord + direction * radius;
        vec2 featherUv = texCoord + direction * (radius + featherRadius);
        vec2 bridgeUv = texCoord + direction * bridgeRadius;

        float coreSample = maskWeight(maskAlpha(coreUv));
        float featherSample = maskWeight(maskAlpha(featherUv));
        float bridgeSample = maskWeight(maskAlpha(bridgeUv));

        outerCore = max(outerCore, coreSample);
        outerFeather = max(outerFeather, featherSample);
        bridgeCore = max(bridgeCore, bridgeSample);
        coreSum += coreSample;
        featherSum += featherSample;
        nearestDepth = min(nearestDepth, min(depthAt(coreUv), depthAt(bridgeUv)));
    }

    float coreAverage = coreSum / float(OUTLINE_SAMPLE_COUNT);
    float featherAverage = featherSum / float(OUTLINE_SAMPLE_COUNT);
    float cornerFill = max(outerCore, mix(bridgeCore, coreAverage, 0.35));
    float shell = saturate(max(cornerFill, max(outerFeather * 0.92, featherAverage * 0.70)) - centerWeight);
    shell = pow(shell, mix(1.35, 0.72, softnessNorm));

    float depthContrast = saturate((depthAt(texCoord) - nearestDepth) * (1.2 + DepthWeight * 5.0));
    float depthBoost = mix(1.0, 1.0 + depthContrast * 1.8, saturate(DepthWeight / 4.0));
    shell *= depthBoost;

    float glow = shell * shell * (0.35 + GlowStrength * 0.95);
    float alpha = saturate(shell + glow) * saturate(Opacity);
    if (alpha <= 0.001) {
        discard;
    }

    vec3 color = outlineBaseColor(texCoord);
    color *= 1.0 + GlowStrength * 0.32 + depthContrast * DepthWeight * 0.14;
    fragColor = vec4(color, alpha);
}
