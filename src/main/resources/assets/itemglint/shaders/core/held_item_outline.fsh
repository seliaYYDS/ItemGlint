#version 330

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform OutlineInfo {
    vec4 OutlineColor;
    vec4 SecondaryColor;
    vec4 Params0;
    vec4 Params1;
    vec4 Params2;
    vec4 Params3;
    vec4 PaletteColors[8];
};

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

vec2 screenSize() {
    return max(Params0.xy, vec2(1.0));
}

float outlineWidth() {
    return Params0.z;
}

float softness() {
    return Params0.w;
}

float alphaThreshold() {
    return Params1.x;
}

float opacity() {
    return Params1.y;
}

float depthWeight() {
    return Params1.z;
}

float glowStrength() {
    return Params1.w;
}

float colorMode() {
    return Params2.x;
}

float colorScrollSpeed() {
    return Params2.y;
}

float timeValue() {
    return Params2.z;
}

float paletteSize() {
    return max(Params2.w, 1.0);
}

float maskAlpha(vec2 uv) {
    return texture(DiffuseSampler, clamp(uv, vec2(0.0), vec2(1.0))).a;
}

float depthAt(vec2 uv) {
    return texture(DepthSampler, clamp(uv, vec2(0.0), vec2(1.0))).r;
}

float maskWeight(float alpha) {
    float feather = 0.02 + softness() * 0.03;
    return smoothstep(alphaThreshold() - feather, alphaThreshold() + feather, alpha);
}

vec3 hsv2rgb(vec3 c) {
    vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    rgb = rgb * rgb * (3.0 - 2.0 * rgb);
    return c.z * mix(vec3(1.0), rgb, c.y);
}

vec3 paletteColor(float index) {
    if (index < 0.5) return PaletteColors[0].rgb;
    if (index < 1.5) return PaletteColors[1].rgb;
    if (index < 2.5) return PaletteColors[2].rgb;
    if (index < 3.5) return PaletteColors[3].rgb;
    if (index < 4.5) return PaletteColors[4].rgb;
    if (index < 5.5) return PaletteColors[5].rgb;
    if (index < 6.5) return PaletteColors[6].rgb;
    return PaletteColors[7].rgb;
}

vec3 sampledScrollColor(vec2 uv) {
    float size = paletteSize();
    if (size < 1.5) {
        return PaletteColors[0].rgb;
    }
    float flow = fract(uv.x * 1.6 + uv.y * 1.1 - timeValue() * 0.12 * colorScrollSpeed());
    float scaled = flow * size;
    float idx0 = floor(scaled);
    float idx1 = mod(idx0 + 1.0, size);
    float blend = fract(scaled);
    return mix(paletteColor(idx0), paletteColor(idx1), blend);
}

vec3 outlineBaseColor(vec2 uv) {
    if (colorMode() < 0.5) {
        return OutlineColor.rgb;
    }

    float flow = uv.x * 18.0 + uv.y * 12.0 - timeValue() * 4.0 * colorScrollSpeed();
    if (colorMode() < 1.5) {
        float dualMix = 0.5 + 0.5 * sin(flow);
        return mix(OutlineColor.rgb, SecondaryColor.rgb, dualMix);
    }
    if (colorMode() < 2.5) {
        float hue = fract(uv.x * 0.22 + uv.y * 0.14 - timeValue() * 0.08 * colorScrollSpeed());
        return hsv2rgb(vec3(hue, 0.85, 1.0));
    }
    return sampledScrollColor(uv);
}

void main() {
    vec2 texel = 1.0 / screenSize();
    float radius = max(outlineWidth(), 0.5);
    float softnessNorm = saturate((softness() - 0.10) / 3.90);
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

    float depthContrast = saturate((depthAt(texCoord) - nearestDepth) * (1.2 + depthWeight() * 5.0));
    float depthBoost = mix(1.0, 1.0 + depthContrast * 1.8, saturate(depthWeight() / 4.0));
    shell *= depthBoost;

    float glow = shell * shell * (0.35 + glowStrength() * 0.95);
    float alpha = saturate(shell + glow) * saturate(opacity());
    if (alpha <= 0.001) {
        discard;
    }

    vec3 color = outlineBaseColor(texCoord);
    color *= 1.0 + glowStrength() * 0.32 + depthContrast * depthWeight() * 0.14;
    fragColor = vec4(color, alpha);
}
