#version 150

#moj_import <fog.glsl>

// How each spell's death changes the body, from the style and time its vertex colour carries.
// Everything works per texel of the mob's own skin, so it stays pixel by pixel as Minecraft is.
// Gravity (4) and Chaos (5) change only the body's shape and opacity, which the vertices carry.
//   1 Heat: chars black, then crumbles away from the top down.
//   2 Growth: drags down into the ground; nothing shows below it.
//   3 Purity: turns white crystal.
//   6 Blood: drained of colour.
//   7 Sunfire: washes gold and cracks with light.
//   8 Poison: sickens green.
//   9 Void: the Ygann mass (Sampler3) takes it over texel by texel, then it dissolves.

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 shade;
in vec4 death;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

float smooth01(float x) { x = clamp(x, 0.0, 1.0); return x * x * (3.0 - 2.0 * x); }
float hash(vec2 p, float k) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + k) * 43758.5453); }

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    int style = int(death.r * 255.0 + 0.5);
    float d = death.g * 255.0 / 40.0;
    // Height as a share of the body: 0 at the feet, 1 at the top of its hitbox.
    float height = death.b * 1.5 - 0.25;
    vec2 texel = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    float sat = 1.0;
    vec3 tint = vec3(0.0);
    float tintAmt = 0.0;
    float dissolve = 0.0;
    vec3 edge = vec3(0.69, 0.42, 0.88);
    if (style == 1) {
        tint = vec3(0.067, 0.051, 0.043); tintAmt = 0.92 * smooth01(d / 1.2);
        if (height > 1.03 - 1.03 * smooth01((d - 1.0) / 1.4)) discard;
    } else if (style == 2) {
        // Sunk by moving the body down; whatever's gone below the ground isn't drawn.
        if (height < 0.0) discard;
    } else if (style == 3) {
        sat = 1.0 - 0.8 * smooth01(d / 0.8); tint = vec3(0.957, 0.949, 1.0); tintAmt = 0.85 * smooth01(d / 0.8);
    } else if (style == 6) {
        sat = 1.0 - smooth01(d / 1.4); tint = vec3(0.54); tintAmt = 0.25 * smooth01(d / 1.4);
    } else if (style == 7) {
        tint = vec3(1.0, 0.776, 0.353); tintAmt = 0.85 * smooth01(d / 0.12);
        dissolve = 0.25 * smooth01((d - 0.05) / 0.15); edge = vec3(1.0, 0.957, 0.753);
    } else if (style == 8) {
        sat = 1.0 - 0.4 * smooth01(d / 1.2); tint = vec3(0.435, 0.682, 0.227); tintAmt = 0.7 * smooth01(d / 1.2);
    }
    color.rgb = mix(vec3(lum), color.rgb, sat);
    color.rgb = mix(color.rgb, tint * (0.35 + lum), tintAmt);
    if (style == 9) {
        // The writhing mass taking over texel by texel, a violet rim where it's spreading.
        float corrupt = smooth01(d / 0.9) * 1.08;
        float m = hash(texel, 17.0);
        if (m < corrupt) color.rgb = texture(Sampler3, fract(texel / 16.0)).rgb * 1.6 + vec3(0.03, 0.0, 0.05);
        else if (m < corrupt + 0.07) color.rgb = edge * 0.8;
        dissolve = smooth01((d - 0.8) / 1.8) * 1.1;
    }
    if (dissolve > 0.0) {
        float n = hash(texel, 3.0);
        if (n < dissolve) discard;
        if (n < dissolve + 0.09) color.rgb = edge;
    }
    color *= shade * ColorModulator;
    color.a *= death.a;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
