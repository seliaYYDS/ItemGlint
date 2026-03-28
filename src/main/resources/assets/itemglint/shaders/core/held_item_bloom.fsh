#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D NearBlurSampler;
uniform sampler2D FarBlurSampler;
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
uniform float AlphaThreshold;
uniform float Opacity;
uniform float GlowStrength;
uniform float BloomStrength;
uniform float BloomRadius;
uniform float ColorMode;
uniform float ColorScrollSpeed;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
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

vec3 bloomBaseColor(vec2 uv) {
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
    float maskSample = texture(DiffuseSampler, texCoord).a;
    float thresholdFeather = 0.02 + AlphaThreshold * 0.06;
    float mask = smoothstep(AlphaThreshold - thresholdFeather, AlphaThreshold + thresholdFeather, maskSample);
    float outsideMask = 1.0 - smoothstep(max(0.0, AlphaThreshold - thresholdFeather * 1.4), AlphaThreshold + thresholdFeather * 0.6, maskSample);
    float nearBlur = texture(NearBlurSampler, texCoord).a;
    float farBlur = texture(FarBlurSampler, texCoord).a;

    float nearHalo = saturate(nearBlur - mask * 0.985);
    float farHalo = saturate(farBlur - mask * 0.92);

    float mergedHalo = max(farHalo, nearHalo * 0.92);
    float radiusFactor = clamp(BloomRadius / 2.0, 0.35, 6.0);
    float spread = smoothstep(0.004, 0.14 + radiusFactor * 0.018, mergedHalo);
    float edgeProximity = saturate(nearHalo / max(farHalo, 0.0001));
    float edgeWeight = 0.30 + 0.70 * pow(edgeProximity, 0.85);
    float glowBoost = 0.55 + GlowStrength * 0.85;
    float alpha = saturate(pow(spread, 1.35) * BloomStrength * edgeWeight * outsideMask * glowBoost) * saturate(Opacity);

    if (alpha <= 0.001) {
        discard;
    }

    float brightness = 0.10
        + GlowStrength * 0.18
        + spread * (0.28 + GlowStrength * 0.10)
        + edgeWeight * spread * (0.55 + GlowStrength * 0.24);
    vec3 color = bloomBaseColor(texCoord) * brightness;
    fragColor = vec4(color, alpha);
}
