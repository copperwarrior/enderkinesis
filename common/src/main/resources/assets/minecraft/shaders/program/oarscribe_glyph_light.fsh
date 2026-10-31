#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Glyph space: the glyph centre at the origin, X along the line, Y out of the writing plane.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
uniform vec3 Colour;
uniform float Emission;
// Gaussian radius of the light about the writing plane.
uniform float Thickness;
// Half the glyph width across the line.
uniform float HalfWidth;
// 1 for a new glyph, 0 when it is gone.
uniform float Fade;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 12;

// Along the line each glyph lights a tent one glyph wide on each side. Tents one glyph apart add up
// to 1, so a trail is one smooth band whose brightness goes from glyph to glyph.
float density(vec3 p) {
    float along = max(1.0 - abs(p.x), 0.0);
    float across = 1.0 - smoothstep(HalfWidth * 0.6, HalfWidth, abs(p.z));
    return along * across * exp(-(p.y * p.y) / (Thickness * Thickness));
}

void main() {
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 toFar = localPos - CamLocal;
    float tFar = length(toFar);
    vec3 dir = toFar / tFar;

    vec3 inv = 1.0 / dir;
    vec3 ta = (BoxMin - CamLocal) * inv;
    vec3 tb = (BoxMax - CamLocal) * inv;
    vec3 tMin = min(ta, tb);
    float t0 = max(max(max(tMin.x, tMin.y), tMin.z), 0.0);
    float t1 = tFar;
    float depth = texture(DepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        t1 = min(t1, length(scene.xyz / scene.w));
    }
    if (t1 <= t0) discard;

    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float stepLen = (t1 - t0) / float(STEPS);
    float light = 0.0;
    for (int i = 0; i < STEPS; i++) {
        light += density(CamLocal + dir * (t0 + (float(i) + jitter) * stepLen));
    }
    vec3 glow = Colour * (Emission * Fade * light * stepLen);
    if (max(max(glow.r, glow.g), glow.b) < 0.002) discard;
    // Premultiplied output with zero alpha (oarscribe_glyph_light.json blend): the light only adds.
    fragColor = vec4(glow, 0.0);
}
