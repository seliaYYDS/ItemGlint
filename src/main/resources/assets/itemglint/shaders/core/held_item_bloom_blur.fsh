#version 330

uniform sampler2D DiffuseSampler;

layout(std140) uniform BlurInfo {
    vec4 Params0;
    vec4 Params1;
};

in vec2 texCoord;
out vec4 fragColor;

vec2 screenSize() {
    return max(Params0.xy, vec2(1.0));
}

vec2 blurDirection() {
    return Params0.zw;
}

float blurRadius() {
    return max(Params1.x, 1.0);
}

void main() {
    vec2 texel = blurDirection() / screenSize();
    float scale = blurRadius();

    float alpha = 0.227027 * texture(DiffuseSampler, texCoord).a;
    alpha += 0.1945946 * texture(DiffuseSampler, texCoord + texel * 1.3846154 * scale).a;
    alpha += 0.1945946 * texture(DiffuseSampler, texCoord - texel * 1.3846154 * scale).a;
    alpha += 0.1216216 * texture(DiffuseSampler, texCoord + texel * 3.2307692 * scale).a;
    alpha += 0.1216216 * texture(DiffuseSampler, texCoord - texel * 3.2307692 * scale).a;
    alpha += 0.0540540 * texture(DiffuseSampler, texCoord + texel * 5.0769230 * scale).a;
    alpha += 0.0540540 * texture(DiffuseSampler, texCoord - texel * 5.0769230 * scale).a;
    alpha += 0.0162162 * texture(DiffuseSampler, texCoord + texel * 6.9230769 * scale).a;
    alpha += 0.0162162 * texture(DiffuseSampler, texCoord - texel * 6.9230769 * scale).a;

    fragColor = vec4(0.0, 0.0, 0.0, alpha);
}
