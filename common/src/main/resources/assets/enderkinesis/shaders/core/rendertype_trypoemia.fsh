#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform float GameTime;
uniform float TrypSeed;
uniform float TrypThreshold;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 worldNormal;
in vec3 partLocal;
in vec3 camLocal;
flat in vec3 partSize;
flat in vec3 partNormal;
flat in float partId;
flat in mat3 partAxes;

out vec4 fragColor;

// Rimmed Hollows, picked in the Trypoemia Looks preview: 64 voxels per block, hole size 0.5x.
const vec3 RIM = vec3(140.0, 200.0, 240.0) / 255.0;
const float VOXEL = 1.0 / 64.0;
const float CELL = 0.05 * 0.5;
const float HOLLOW_CELL = 5.0;
const float DEPTH = 0.16 * CELL / 0.05;
const float RING_SECTORS = 7.0;
const float RING_FILL = 0.5;
const int MAX_STEPS = 64;

float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 h22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(.1031, .1030, .0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
float h31(vec3 p) { p = fract(p * .1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
float vn3(vec3 p) {
    vec3 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
    return mix(mix(mix(h31(i), h31(i + vec3(1, 0, 0)), f.x), mix(h31(i + vec3(0, 1, 0)), h31(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h31(i + vec3(0, 0, 1)), h31(i + vec3(1, 0, 1)), f.x), mix(h31(i + vec3(0, 1, 1)), h31(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
float vn2(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
    return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec3 p) { return .62 * vn3(p) + .38 * vn3(p * 2.3 + 7.1); }

vec3 UA = vec3(1, 0, 0), VA = vec3(0, 1, 0);
vec2 PID = vec2(0);

float hollowRadius(vec2 cid, float a) {
    float r = .16 + .3 * pow(h21(cid + 9.1), 1.4);
    return r * (1. + .36 * (vn2(vec2(a * 1.3 + h21(cid + 2.) * 20., h21(cid + 6.) * 9.)) - .5));
}

// x: depth into the nearest hole (> 0 inside), y: distance in hole radii, w: 1 for a hollow, .6 for a ring hole.
vec4 hollows(vec2 uv) {
    float cs = CELL * HOLLOW_CELL;
    vec2 q = uv / cs;
    q += (vec2(vn2(q * 1.7 + PID), vn2(q * 1.7 + PID + 19.7)) - .5) * .16;
    vec2 ip = floor(q), fp = fract(q);
    float tH = 1e3; vec2 dH = vec2(0); vec2 cH = vec2(0);
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        vec2 g = vec2(i, j); vec2 cid = ip + g + PID;
        vec2 fpt = g + .2 + .6 * h22(cid);
        vec2 cuv = (ip + fpt) * cs;
        float field = fbm((UA * cuv.x + VA * cuv.y) * 4.2 + (partId + TrypSeed) * 3.17);
        float k = smoothstep(TrypThreshold, TrypThreshold + .2, field);
        if (h21(cid + 3.7) > k * .85) continue;
        vec2 d = fp - fpt;
        float t = length(d) / hollowRadius(cid, atan(d.y, d.x));
        if (t < tH) { tH = t; dH = d; cH = cid; }
    }
    if (tH >= 1e3) return vec4(-1., 4., 4., 0.);
    if (tH < 1.) return vec4(1. - tH, tH, 4., 1.);
    float ang = atan(dH.y, dH.x);
    float sector = floor((ang + 3.14159) / 6.28318 * RING_SECTORS);
    float tS = 1e3;
    for (int k = -1; k <= 1; k++) {
        float sc = sector + float(k);
        vec2 sid = cH + vec2(sc * 7.13, sc * 3.31);
        if (h21(sid) > RING_FILL) continue;
        float ac = (sc + .5 + (h21(sid + 1.) - .5) * .6) / RING_SECTORS * 6.28318 - 3.14159;
        float rr = hollowRadius(cH, ac) * (1.32 + .14 * h21(sid + 2.));
        float rs = (.03 + .07 * pow(h21(sid + 3.), 1.5)) * (.8 + .4 * h21(cH + 5.));
        tS = min(tS, length(dH - vec2(cos(ac), sin(ac)) * rr) / rs);
    }
    if (tS < 1.) return vec4(1. - tS, tS, 4., .6);
    return vec4(min(1. - tS, 1. - tH), min(1. + (tH - 1.) * 3., tS), 4., 1.);
}

// Side 0 is the shared layout of a flat plane, which has no inside to carve.
vec4 holeAt(int a, float side, vec3 p) {
    if (a == 0) { UA = vec3(0, 0, 1); VA = vec3(0, 1, 0); }
    else if (a == 1) { UA = vec3(1, 0, 0); VA = vec3(0, 0, 1); }
    else { UA = vec3(1, 0, 0); VA = vec3(0, 1, 0); }
    float part = partId + TrypSeed;
    float fa = float(a);
    PID = vec2(part * 37.7 + fa * 11.3 + side * 5.1, part * 5.9 + fa * 23.1 + side * 17.3);
    return hollows(vec2(dot(p, UA), dot(p, VA)));
}

// A voxel is empty when a bowl of DEPTH from any face reaches it; on a thin part, bowls from both sides can meet.
bool solid(vec3 c) {
    vec3 p = (c + .5) * VOXEL;
    for (int a = 0; a < 3; a++) {
        float sz = partSize[a];
        if (sz < VOXEL) continue;
        float lo = p[a], hi = sz - p[a];
        if (lo < DEPTH && lo < DEPTH * clamp(holeAt(a, -1., p).x * 2.5, 0., 1.)) return false;
        if (hi < DEPTH && hi < DEPTH * clamp(holeAt(a, 1., p).x * 2.5, 0., 1.)) return false;
    }
    return true;
}

// Same mix as minecraft_mix_light.
float faceLight(vec3 n) {
    vec3 l0 = normalize(Light0_Direction), l1 = normalize(Light1_Direction);
    return min(1., (max(0., dot(l0, n)) + max(0., dot(l1, n))) * .6 + .4);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    vec3 skin = tex.rgb * vertexColor.rgb;
    vec3 col = skin * faceLight(normalize(worldNormal));

    if (partId >= 0.) {
        vec3 an = abs(partNormal);
        int na = an.x > .5 ? 0 : (an.y > .5 ? 1 : 2);
        // Mirrored cubes flip their normals, so the inward side comes from the position instead.
        float out_ = partLocal[na] > partSize[na] * .5 ? 1. : -1.;
        vec3 inward = vec3(0); inward[na] = -out_;
        vec3 cell = floor((partLocal + inward * VOXEL * .01) / VOXEL);
        bool flat_ = partSize[na] < VOXEL;
        if (flat_ && holeAt(na, 0., (cell + .5) * VOXEL).x > 0.) {
            discard;
        }
        if (flat_ || solid(cell)) {
            vec4 P = holeAt(na, flat_ ? 0. : out_, (cell + .5) * VOXEL);
            float throb = step(.5, .5 + .5 * sin(GameTime * 1200. * 4.2));
            vec3 rim = mix(RIM, vec3(1.), .12 * throb);
            vec3 tint = skin;
            if (P.y - 1. < .35 && P.y < 3.9) tint = mix(skin, rim, .35);
            else if (P.w > .5 && P.y < 2.5) tint = mix(skin, rim, .12);
            col = tint * faceLight(normalize(worldNormal));
        } else {
            vec3 rd = normalize(partLocal - camLocal);
            rd = sign(rd) * max(abs(rd), vec3(1e-5));
            vec3 st = sign(rd);
            vec3 tDelta = abs(VOXEL / rd);
            vec3 ro = partLocal + inward * VOXEL * .01;
            vec3 nextT = ((cell + max(st, 0.)) * VOXEL - ro) / rd;
            vec3 cells = floor(partSize / VOXEL + .001);
            int ax = na;
            bool hit = false;
            for (int i = 0; i < MAX_STEPS; i++) {
                if (nextT.x < nextT.y && nextT.x < nextT.z) { ax = 0; cell.x += st.x; nextT.x += tDelta.x; }
                else if (nextT.y < nextT.z) { ax = 1; cell.y += st.y; nextT.y += tDelta.y; }
                else { ax = 2; cell.z += st.z; nextT.z += tDelta.z; }
                if (any(lessThan(cell, vec3(0))) || any(greaterThanEqual(cell, cells))) break;
                if (solid(cell)) { hit = true; break; }
            }
            if (!hit) {
                discard;
            }
            vec3 hn = vec3(0); hn[ax] = -st[ax];
            float depth = abs((cell[na] + .5) * VOXEL - partLocal[na]);
            float df = clamp(depth / max(DEPTH, VOXEL), 0., 1.);
            col = mix(skin, RIM, .35) * (.9 - .5 * df) * faceLight(normalize(partAxes * hn));
        }
    }

    vec4 color = vec4(col, tex.a * vertexColor.a) * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
