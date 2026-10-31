#version 150

// Marched in the VolumeProxy box. The world behind comes from the screen copy, bent toward the core.

uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;
uniform mat4 ViewProj;
uniform vec2 DepthParams;
uniform vec2 ScreenSize;
// Camera-relative, world axes.
uniform vec3 SunCentre;
// x: core radius. y: half size of the box.
uniform vec2 Radii;
// Disk tip, as a fraction of a turn.
uniform float Tip;
uniform float Shown;
uniform float SpellTicks;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

// Seconds on the spell clock, because the shapes were tuned in seconds.
#define T (SpellTicks * 0.05)

const int STEPS = 28;
// Linear colours. HOT is SUN 55% toward white, as the Sunfire beam uses.
const vec3 SUN = vec3(0.797, 0.471, 0.041);
const vec3 HOT = vec3(0.906, 0.735, 0.396);
const vec3 GRAV = vec3(0.389, 0.349, 0.618);
// All sizes below are in core radii.
const float ENVELOPE = 3.6;
const float TILT = 0.17;
const float BEND = 0.6;

vec3 up;

// Hash without sin, so it comes out the same on every GPU.
float hash(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 4; i++) { s += a * n3(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
    return s / 0.9375;
}

float fbm3(vec3 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 3; i++) { s += a * n3(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
    return s / 0.875;
}

vec3 fire(float t) {
    t = max(t, 0.0);
    vec3 c = mix(GRAV * 0.7, SUN, smoothstep(0.0, 0.3, t));
    c = mix(c, HOT, smoothstep(0.95, 1.25, t));
    c = mix(c, vec3(1.0), smoothstep(1.3, 1.5, t));
    return c * 1.4 * t;
}

vec2 sph(vec3 ro, vec3 rd, float r) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    float h = b * b - c;
    if (h < 0.0) return vec2(-1.0);
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

vec3 turn(vec3 v, vec3 axis, float angle) {
    float c = cos(angle), s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Distance to the scene along the unit view ray [d], world axes. Clip w is view depth.
float sceneDistance(vec3 d) {
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return DepthParams.y / (ndc + DepthParams.x) / (ViewProj * vec4(d, 0.0)).w;
}

// Project a direction, not a point. The projection translation (view bobbing) moves a near point much more than the world.
vec2 screenOfDir(vec3 d) {
    vec4 clip = ViewProj * vec4(d, 0.0);
    return clip.xy / max(clip.w, 1e-4) * 0.5 + 0.5;
}

vec3 loopsEm(vec3 q, float nWidth, float nFlicker) {
    float e = 0.0;
    float wd = 0.05 + 0.03 * nWidth;
    for (int k = 0; k < 5; k++) {
        float fk = float(k);
        float life = fract(T * 0.07 + fk * 0.21);
        vec3 dir = normalize(vec3(hash(vec3(fk, 1.0, 1.0)) - 0.5, hash(vec3(fk, 2.0, 1.0)) - 0.5, hash(vec3(fk, 3.0, 1.0)) - 0.5));
        vec3 nrm = normalize(cross(dir, vec3(hash(vec3(fk, 4.0, 1.0)) - 0.5, 1.0, hash(vec3(fk, 5.0, 1.0)) - 0.5)));
        vec3 o = q - dir * 0.95;
        float dn = dot(o, nrm);
        float d = length(vec2(length(o - nrm * dn) - (0.3 + 0.7 * life) * 0.6, dn));
        e += exp(-d * d / (wd * wd)) * sin(life * 3.1416);
    }
    e *= (0.6 + 0.8 * nFlicker) * step(1.0, length(q));
    return fire(0.9 + 0.4 * e) * e * 1.2;
}

vec3 ringsEm(vec3 q) {
    vec3 L = vec3(0.0);
    for (int k = 0; k < 3; k++) {
        float fk = float(k);
        float a = T * (0.35 + 0.2 * fk) * (k == 1 ? -1.0 : 1.0);
        vec3 nrm = normalize(vec3(sin(a + fk * 2.0), cos(fk * 1.7) * 1.5, cos(a + fk * 2.0)));
        float dn = dot(q, nrm);
        vec3 o = q - nrm * dn;
        float d = length(vec2(length(o) - (1.5 + 0.4 * fk), dn));
        // Past this, the ring light is less than e^-6 of its peak.
        if (d > 0.35) continue;
        vec3 e1 = normalize(cross(nrm, vec3(0.3, 0.1, 1.0)));
        vec3 e2 = cross(nrm, e1);
        // Sample on a circle: an angle from atan wraps, and the ring breaks at the wrap.
        float s = atan(dot(o, e2), dot(o, e1)) + T * (1.0 + fk * 0.4);
        vec2 c = vec2(cos(s), sin(s));
        float fl = 0.6 * n3(vec3(c * 3.0, d * 4.0 + T * 0.8 + fk * 3.0)) + 0.4 * n3(vec3(c * 7.0, d * 9.0 + T * 1.3));
        float wd = 0.06 + 0.08 * fl;
        L += fire(0.85 + 0.4 * fl) * exp(-d * d / (wd * wd)) * fl * 2.2;
    }
    return L;
}

// Gas light at [q] and absorption [ab], both per core radius.
vec3 gasAt(vec3 q, out float ab) {
    float r = length(q);
    float fall = smoothstep(1.0, 2.6, r);
    fall *= fall;

    float gran = fbm(q * 1.8 + vec3(0.0, 0.0, T * 0.05));
    float dsh = smoothstep(3.6, 1.2, r);
    float dg = (smoothstep(3.6, 2.6, r) * 0.35 + 0.65 * dsh) * (0.35 + 0.65 * gran) * (0.55 + 0.45 * (0.5 + 0.5 * sin(r * 7.0 + T * 1.4)));
    float ang = atan(q.z, q.x) + 1.5 / r + T * 0.2;
    dg += 0.6 * pow(max(0.0, sin(ang * 3.0 + q.y * 1.5)), 12.0) * smoothstep(3.0, 1.1, r);
    dg = mix(dg, dsh * (0.35 + 0.65 * gran), 0.5) * 0.2;
    float tg = mix(0.45, 1.25, smoothstep(3.4, 1.0, r));

    float sw = T * 0.5 + 2.2 / r;
    float cs = cos(sw), sn = sin(sw);
    vec3 fq = vec3(cs * q.x - sn * q.z, q.y * 1.2, sn * q.x + cs * q.z);
    float df = fbm3(vec3(fq.xz * 1.1, fq.y) + vec3(0.0, 0.0, log(r) * 2.0 + T * 0.6));
    df = smoothstep(0.45, 0.85, df) * smoothstep(3.2, 1.3, r) * (1.0 + 1.0 / (r * r)) * 0.38;
    float tf = clamp(1.25 / r + 0.3 * df, 0.0, 1.4);

    float h = dot(q, up);
    float rp = length(q - up * h);
    float diskLight = exp(-h * h / 0.3) * smoothstep(4.4, 2.2, rp) * smoothstep(1.2, 2.0, rp);
    vec3 rings = ringsEm(q);
    vec3 em = (fire(tg) * dg * 0.9 + fire(tf) * df * 2.4) * fall;
    em += (dg + df) * (SUN * 1.4 * diskLight + rings * 0.5) * (0.3 + 0.7 * fall);
    em += rings * 1.5;
    // Loops reach no further than 1.9 from the middle.
    if (r < 2.0) em += loopsEm(q, n3(q * 6.0 + vec3(T * 0.3)), n3(q * 9.0 + vec3(T * 0.4))) * 0.8;
    ab = dg * 0.9 + df * 1.2 + 2.5 * exp(-(r - 1.0) * 7.0);
    return em;
}

// Disk light where the ray crosses its plane, and the distance there. w < 0 means no crossing.
vec4 diskAt(vec3 ro, vec3 rd, float R, float before) {
    float den = dot(rd, up);
    if (abs(den) < 1e-4) return vec4(0.0, 0.0, 0.0, -1.0);
    float t = -dot(ro, up) / den;
    if (t <= 0.0 || t > before) return vec4(0.0, 0.0, 0.0, -1.0);
    vec3 p = ro + rd * t;
    float r = length(p) / R;
    if (r < 1.3 || r > 4.2) return vec4(0.0, 0.0, 0.0, -1.0);
    float dop = 1.0 + 0.75 * dot(normalize(cross(up, p)), -rd);
    vec3 ax = normalize(cross(up, vec3(0.0, 0.0, 1.0)));
    float ang = atan(dot(p, cross(up, ax)), dot(p, ax));
    float a = ang - T * 0.4 / r;
    float tex = fbm(vec3(r * 3.0, cos(a) * 2.0, sin(a) * 2.0));
    float bands = 0.6 + 0.4 * sin(r * 14.0 + tex * 6.0);
    float I = smoothstep(4.2, 2.0, r) * smoothstep(1.3, 1.6, r) * (0.5 + tex) * bands / (r * 0.7);
    // No fade near the black, so the near side of the disk burns across the core.
    return vec4(fire(0.7 + 0.5 / r) * I * dop * dop * 1.4 * 1.6 * smoothstep(1.3, 1.5, r), t);
}

void main() {
    float R = Radii.x, box = Radii.y;
    vec3 rd = normalize(viewPos);
    vec3 ro = -SunCentre;
    float yaw = Tip * 6.2832;
    up = turn(vec3(0.0, 1.0, 0.0), vec3(cos(yaw), 0.0, sin(yaw)), TILT);

    float scene = sceneDistance(rd);
    float along = -dot(ro, rd);
    vec3 cl = ro + rd * along;
    float b = length(cl);
    float x = b / R;
    vec2 core = sph(ro, rd, R);
    bool black = core.y > 0.0 && core.x < scene;
    float coreT = black ? max(core.x, 0.0) : 1e9;
    vec2 sh = sph(ro, rd, R * ENVELOPE);
    vec4 dk = diskAt(ro, rd, R, min(coreT, scene));
    if (!black && sh.y <= 0.0 && dk.w < 0.0) discard;

    vec3 L = vec3(0.0);
    float tr = 1.0;
    vec3 diskSeen = vec3(0.0);
    bool diskDone = dk.w < 0.0;
    if (sh.y > 0.0) {
        float t0 = max(sh.x, 0.0);
        float t1 = min(min(sh.y, coreT), scene);
        float dt = (t1 - t0) / float(STEPS);
        float t = t0 + dt * hash(vec3(gl_FragCoord.xy, SpellTicks));
        for (int i = 0; i < STEPS; i++) {
            if (t1 <= t0) break;
            if (!diskDone && t > dk.w) { diskSeen = dk.rgb * tr; L += diskSeen; diskDone = true; }
            float ab;
            vec3 em = gasAt((ro + rd * t) / R, ab);
            L += tr * em * dt / R;
            tr *= exp(-ab * dt / R);
            t += dt;
        }
    }
    if (!diskDone) { diskSeen = dk.rgb * tr; L += diskSeen; }

    bool edgeShown = along > 0.0 && along < scene;
    vec3 hair = edgeShown ? HOT * 2.2 * exp(-pow((x - 1.0) / 0.014, 2.0)) : vec3(0.0);
    vec3 behind = vec3(0.0);
    if (black) {
        L = (L - diskSeen) * 0.08 + diskSeen + hair;
    } else {
        vec3 bent = rd;
        float dim = 1.0;
        if (edgeShown && x > 1.0) {
            float e = x - 1.0;
            vec3 w = normalize(-ro);
            vec3 u = normalize(cross(w, vec3(0.0, 1.0, 0.0)));
            float sa = atan(dot(cl, cross(u, w)), dot(cl, u));
            // Fade everything past the envelope to zero before the box edge, where the volume stops.
            float fade = 1.0 - smoothstep(1.8 * R, 3.5 * R, b);
            L += fire(0.9) * exp(-pow((x - 1.35) / 0.3, 2.0)) * 0.35 * tr;
            vec3 glow = SUN * 1.6 * exp(-e * 4.0) + GRAV * 0.5 * exp(-e * 1.4) * 0.4;
            glow += SUN * pow(n3(vec3(cos(sa) * 18.0, sin(sa) * 18.0, T * 0.15)), 4.0) * exp(-e * 0.9) * 0.7;
            L += (glow * 0.5 * fade + hair * 0.6) * tr;
            dim = 1.0 - 0.35 * exp(-e * 1.2) * fade;
            float s = exp(-e * 1.5) * 0.015;
            vec3 sd = normalize(rd + s * vec3(n3(rd * 30.0 + vec3(0.0, T * 0.6, 0.0)) - 0.5, n3(rd * 30.0 + vec3(5.0, T * 0.6, 0.0)) - 0.5, 0.0));
            bent = normalize(sd - cl / b * BEND * R / max(b, R) * fade);
        }
        // Only the bend offset moves the sample, so with no bend it reads this pixel exactly.
        vec2 uv = gl_FragCoord.xy / ScreenSize + screenOfDir(bent) - screenOfDir(rd);
        vec3 seen = texture(SceneSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
        behind = seen * seen * dim * tr;
    }

    // Compress only the brightness, so bright gold stays gold and does not go white.
    float lum = max(dot(L, vec3(0.2126, 0.7152, 0.0722)), 1e-4);
    vec3 lit = min(L * (1.0 - exp(-lum * 1.15)) / lum, vec3(1.0));
    // The same square to linear and back, so pixels with no light stay unchanged.
    fragColor = vec4(sqrt(min(lit + behind, vec3(1.0))), Shown);
}
