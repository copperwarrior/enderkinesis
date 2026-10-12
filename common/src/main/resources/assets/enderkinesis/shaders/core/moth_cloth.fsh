#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float WallTime;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
// World position mod 2000 blocks; see GLINT_CELL_PERIOD.
in vec3 worldPos;

out vec4 fragColor;

// Warm yellow glint colour. Additively blended on top of the base wool
// that the solid pass rendered underneath.
const vec3 GLINT_COLOR = vec3(1.0, 0.90, 0.35);

// Peak additive contribution at the brightest wall pixel.
const float GLINT_STRENGTH = 0.30;

const float GLINT_SCALE = 0.55;
const float GLINT_WALL_WIDTH = 0.09;
const float GLINT_SCROLL_SPEED = 0.35;
const float GLINT_ORBIT_RADIUS = 0.9;
const float EDGE_INNER = 0.01;
const float EDGE_OUTER = 0.12;
const float PIXELS_PER_BLOCK = 16.0;
// Cells per pattern repeat. worldPos wraps every 2000 blocks (MothClothOverlayRenderer), which moves
// the cells by 1100/550/330 along x/z/y at GLINT_SCALE 0.55 — all multiples of 110, so no seam.
const float GLINT_CELL_PERIOD = 110.0;

vec2 hash22(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)),
             dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

float voronoiEdgeDist(vec2 p) {
    vec2 n = floor(p);
    vec2 f = fract(p);
    float f1Sq = 8.0;
    float f2Sq = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = hash22(mod(n + g, GLINT_CELL_PERIOD));
            vec2 diff = g + o - f;
            float dSq = dot(diff, diff);
            if (dSq < f1Sq) { f2Sq = f1Sq; f1Sq = dSq; }
            else if (dSq < f2Sq) { f2Sq = dSq; }
        }
    }
    return sqrt(f2Sq) - sqrt(f1Sq);
}

void main() {
    // We only need the texture's alpha (wool cells are fully opaque, so
    // alpha ≥ 0.1) — the RGB is discarded, we output pure glint colour.
    vec4 base = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (base.a < 0.1) discard;

    float phase = WallTime * GLINT_SCROLL_SPEED;
    vec2 timeOffset = vec2(cos(phase), sin(phase)) * GLINT_ORBIT_RADIUS;

    vec3 pixelWorld = (floor(worldPos * PIXELS_PER_BLOCK) + 0.5) / PIXELS_PER_BLOCK;
    vec2 sampleUV = pixelWorld.xy * GLINT_SCALE
                  + vec2(pixelWorld.z * GLINT_SCALE * 0.5,
                         pixelWorld.z * GLINT_SCALE * 0.3)
                  + timeOffset;
    float edgeDist = voronoiEdgeDist(sampleUV);
    float wallGlow = 1.0 - smoothstep(0.0, GLINT_WALL_WIDTH, edgeDist);

    vec3 d = min(fract(worldPos), 1.0 - fract(worldPos));
    float mn = min(min(d.x, d.y), d.z);
    float mx = max(max(d.x, d.y), d.z);
    float distToFaceEdge = d.x + d.y + d.z - mn - mx;
    float edgeMask = 1.0 - smoothstep(EDGE_INNER, EDGE_OUTER, distToFaceEdge);

    float glint = wallGlow * edgeMask * GLINT_STRENGTH;
    if (glint < 0.001) discard;

    // Additive blend expects `fragColor = colorToAdd * alpha`. Give it
    // the full glint colour scaled by intensity so it's added on top of
    // the solid-pass wool.
    vec4 color = vec4(GLINT_COLOR * glint, glint);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
