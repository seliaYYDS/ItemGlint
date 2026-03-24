#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform vec2 ScreenSize;
uniform vec4 OutlineColor;
uniform vec4 SecondaryColor;
uniform float OutlineWidth;
uniform float Softness;
uniform float AlphaThreshold;
uniform float Opacity;
uniform float DepthWeight;
uniform float GlowStrength;
uniform float ColorMode;
uniform float ColorScrollSpeed;
uniform float Time;
uniform float PulseSpeed;
uniform float PulseAmount;

in vec2 texCoord;

out vec4 fragColor;

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

vec3 outlineBaseColor(vec2 uv) {
    if (ColorMode < 0.5) {
        return OutlineColor.rgb;
    }

    float flow = uv.x * 18.0 + uv.y * 12.0 - Time * 4.0 * ColorScrollSpeed;
    if (ColorMode < 1.5) {
        float dualMix = 0.5 + 0.5 * sin(flow);
        return mix(OutlineColor.rgb, SecondaryColor.rgb, dualMix);
    }

    float hue = fract(uv.x * 0.22 + uv.y * 0.14 - Time * 0.08 * ColorScrollSpeed);
    return hsv2rgb(vec3(hue, 0.85, 1.0));
}

void main() {
    vec2 texel = 1.0 / max(ScreenSize, vec2(1.0));

    float pulsePhase = sin(Time * PulseSpeed * 6.2831853);
    float pulseWidth = 1.0 + pulsePhase * PulseAmount * 0.35;
    float pulseBoost = 1.0 + max(0.0, pulsePhase) * PulseAmount * 2.4;

    float radius = max(OutlineWidth * pulseWidth, 0.5);
    float softnessNorm = saturate((Softness - 0.10) / 3.90);
    float featherRadius = mix(0.45, 2.8, softnessNorm);

    vec2 stepX = vec2(texel.x, 0.0);
    vec2 stepY = vec2(0.0, texel.y);
    vec2 diagA = vec2(texel.x, texel.y) * 0.70710678;
    vec2 diagB = vec2(texel.x, -texel.y) * 0.70710678;

    vec2 offsets[8] = vec2[](
        stepX, -stepX, stepY, -stepY,
        diagA, -diagA, diagB, -diagB
    );

    float centerWeight = maskWeight(maskAlpha(texCoord));
    float outerCore = 0.0;
    float outerFeather = 0.0;
    float nearestDepth = depthAt(texCoord);

    for (int i = 0; i < 8; i++) {
        vec2 coreUv = texCoord + offsets[i] * radius;
        vec2 featherUv = texCoord + offsets[i] * (radius + featherRadius);

        outerCore = max(outerCore, maskWeight(maskAlpha(coreUv)));
        outerFeather = max(outerFeather, maskWeight(maskAlpha(featherUv)));
        nearestDepth = min(nearestDepth, depthAt(coreUv));
    }

    float shell = saturate(max(outerCore, outerFeather * 0.92) - centerWeight);
    shell = pow(shell, mix(1.35, 0.72, softnessNorm));

    float depthContrast = saturate((depthAt(texCoord) - nearestDepth) * (1.2 + DepthWeight * 5.0));
    float depthBoost = mix(1.0, 1.0 + depthContrast * 1.8, saturate(DepthWeight / 4.0));
    shell *= depthBoost;

    float glow = shell * shell * (0.35 + GlowStrength * 0.95);
    float alpha = saturate((shell * pulseBoost + glow) * Opacity);
    if (alpha <= 0.001) {
        discard;
    }

    vec3 color = outlineBaseColor(texCoord);
    color *= 1.0 + GlowStrength * 0.32 + depthContrast * DepthWeight * 0.14 + max(0.0, pulsePhase) * PulseAmount * 1.4;
    fragColor = vec4(color, alpha);
}
