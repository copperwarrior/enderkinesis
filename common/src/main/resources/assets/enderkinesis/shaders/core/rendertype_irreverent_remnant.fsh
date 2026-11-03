#version 150

in vec3 surfaceNormal;
in vec4 lightColour;
in vec2 faceCoord;
in float noiseSeed;
in float alpha;

uniform vec4 GlowColor;
uniform vec3 EntityLight0;
uniform vec3 EntityLight1;
uniform float Thickness;
uniform float NoiseStrength;
uniform float NoiseScale;
uniform float NoiseLevels;
uniform vec4 CoreColor;
uniform float CorePower;
uniform float CoreStrength;

out vec4 fragColor;

float hash(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

/** Value noise with smoothstep blending between lattice points. */
float valueNoise(vec2 p, float salt) {
    vec2 cell = floor(p);
    vec2 t = fract(p);
    t = t * t * (3.0 - 2.0 * t);
    float a = hash(vec3(cell, salt));
    float b = hash(vec3(cell + vec2(1.0, 0.0), salt));
    float c = hash(vec3(cell + vec2(0.0, 1.0), salt));
    float d = hash(vec3(cell + vec2(1.0, 1.0), salt));
    return mix(mix(a, b, t.x), mix(c, d, t.x), t.y);
}

void main() {
    vec3 normal = normalize(surfaceNormal);

    // Vanilla's minecraft_mix_light: two fixed lights over an ambient floor, as on entities.
    float light0 = max(0.0, dot(normalize(EntityLight0), normal));
    float light1 = max(0.0, dot(normalize(EntityLight1), normal));
    vec3 colour = GlowColor.rgb * min(1.0, (light0 + light1) * 0.6 + 0.4);

    // Light held inside the body, so the cubes glow a little of their own accord.
    colour += CoreColor.rgb * pow(Thickness, CorePower) * CoreStrength;

    // Smooth noise read at each world pixel's centre, from the face's corner, then
    // cut into NoiseLevels tones. Seeded per cube and per face.
    vec2 texel = floor(faceCoord * 16.0) + 0.5;
    float faceId = dot(normal, vec3(1.0, 2.0, 3.0));
    float smoothNoise = valueNoise(texel / NoiseScale, faceId + noiseSeed * 97.0);
    float tone = min(floor(smoothNoise * NoiseLevels), NoiseLevels - 1.0) / (NoiseLevels - 1.0);
    colour *= 1.0 + (tone * 2.0 - 1.0) * NoiseStrength;

    // Clamped before the lightmap, or a saturated sum cannot be scaled back down.
    colour = min(colour, vec3(1.0));
    colour *= lightColour.rgb;

    fragColor = vec4(colour, alpha);
}
