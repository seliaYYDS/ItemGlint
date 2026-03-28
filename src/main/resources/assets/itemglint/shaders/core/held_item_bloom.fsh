#version 330

uniform sampler2D DiffuseSampler;
uniform sampler2D NearBlurSampler;
uniform sampler2D FarBlurSampler;

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

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

float alphaThreshold() {
    return Params1.x;
}

float opacity() {
    return Params1.y;
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

float bloomStrength() {
    return Params3.x;
}

float bloomRadius() {
    return Params3.y;
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
    float size = max(paletteSize(), 1.0);
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

vec3 bloomBaseColor(vec2 uv) {
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
    float maskSample = texture(DiffuseSampler, texCoord).a;
    float thresholdFeather = 0.02 + alphaThreshold() * 0.06;
    float mask = smoothstep(alphaThreshold() - thresholdFeather, alphaThreshold() + thresholdFeather, maskSample);
    float outsideMask = 1.0 - smoothstep(max(0.0, alphaThreshold() - thresholdFeather * 1.4), alphaThreshold() + thresholdFeather * 0.6, maskSample);
    float nearBlur = texture(NearBlurSampler, texCoord).a;
    float farBlur = texture(FarBlurSampler, texCoord).a;

    float nearHalo = saturate(nearBlur - mask * 0.985);
    float farHalo = saturate(farBlur - mask * 0.92);

    float mergedHalo = max(farHalo, nearHalo * 0.92);
    float radiusFactor = clamp(bloomRadius() / 2.0, 0.35, 6.0);
    float spread = smoothstep(0.004, 0.14 + radiusFactor * 0.018, mergedHalo);
    float edgeProximity = saturate(nearHalo / max(farHalo, 0.0001));
    float edgeWeight = 0.30 + 0.70 * pow(edgeProximity, 0.85);
    float glowBoost = 0.55 + glowStrength() * 0.85;
    float alpha = saturate(pow(spread, 1.35) * bloomStrength() * edgeWeight * outsideMask * glowBoost) * saturate(opacity());

    if (alpha <= 0.001) {
        discard;
    }

    float brightness = 0.10
        + glowStrength() * 0.18
        + spread * (0.28 + glowStrength() * 0.10)
        + edgeWeight * spread * (0.55 + glowStrength() * 0.24);
    vec3 color = bloomBaseColor(texCoord) * brightness;
    fragColor = vec4(color, alpha);
}
