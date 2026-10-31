#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Beam space: SEND at the origin, RECV at (BeamLen, 0, 0), the sag on +Y.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
uniform float BeamLen;
uniform vec3 Control;
// One hourglass tube: EnvelopeRadius at the orbs, WaistRadius at the middle. Past each end the
// clamped curve parameter closes the tube in a round cap over the orb.
uniform float EnvelopeRadius;
uniform float WaistRadius;
// This tube's part of the whole beam (0 at SEND, 1 at RECV) when anchors bend it into pieces.
// The hourglass follows the whole beam, so the round caps at an anchor have the same radius.
uniform vec2 ProfileSpan;
// Emissive Gaussian halo in the link colours. It stays optically thin, so brightness falls off
// smoothly. It widens from HaloRadius at the middle to HaloEndRadius at the orbs, on the cloud's
// hourglass profile, and closes in a round cap around each orb.
uniform float HaloRadius;
uniform float HaloEndRadius;
uniform float HaloEmission;
// This beam's part of the halo at each orb (SEND, RECV), so the beams of an orb add up to one envelope.
uniform vec2 OrbShare;
// Cloud brightness at the tube edge. It rises to 1 at the axis; the hue does not change.
uniform float EdgeBrightness;
uniform float Density;
uniform float Opacity;
uniform mat4 AccentsLo;
uniform mat4 AccentsHi;
// One or more. Each colour gets an equal angular part of the cross-section.
uniform int AccentCount;
// Turn of the colour parts about the beam axis, in radians.
uniform float Spin;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

// Past this many radii a Gaussian adds no visible light; the step skips its integral.
const float GAUSS_REACH = 3.0;
// Bounds the march. A step never falls below the remaining length over the remaining steps.
const int MAX_STEPS = 40;
const float MIN_SKIP = 0.02;
// Outside the tube, d - r overestimates the gap where the curve bends, so skip only part of it.
const float SKIP_SCALE = 0.6;
const float INSIDE_STEP = 0.35;
const float OPAQUE_THROUGH = 0.02;
const float TAU = 6.28318531;

vec3 accent(int i) {
    return (i < 4 ? AccentsLo[i] : AccentsHi[i - 4]).rgb;
}

// Each colour is pure at the centre of its part and blends into its neighbours at the edges.
vec3 linkColour(vec2 around) {
    // The small x bias keeps atan defined on the curve itself.
    around.x += 1.0e-6;
    float n = float(AccentCount);
    float part = fract((atan(around.y, around.x) + Spin) / TAU) * n - 0.5;
    float first = floor(part);
    return mix(accent(int(mod(first, n))), accent(int(mod(first + 1.0, n))), smoothstep(0.0, 1.0, part - first));
}

// Winitzki's approximation, error under 2e-4.
float erfApprox(float x) {
    float x2 = x * x;
    float ax2 = 0.147 * x2;
    return sign(x) * sqrt(1.0 - exp(-x2 * (1.27323954 + ax2) / (1.0 + ax2)));
}

// Exact integral of exp(-dist² / radius²) along w + dir * [0, len], with the curve straight
// along its tangent over the segment. Right at any step length.
float gaussIntegral(vec3 w, vec3 dir, vec3 tangent, float len, float radius) {
    vec3 wp = w - dot(w, tangent) * tangent;
    vec3 dp = dir - dot(dir, tangent) * tangent;
    float a = dot(dp, dp);
    float b = dot(wp, dp);
    float c = dot(wp, wp);
    float r2 = radius * radius;
    if (a < 1.0e-6) return len * exp(-c / r2);
    float sa = sqrt(a);
    float scale = 1.0 / (sa * radius);
    float closest = max(c - b * b / a, 0.0);
    // sqrt(pi) / 2 * radius / sqrt(a), from completing the square in the path length.
    return exp(-closest / r2) * 0.886226925 * radius / sa * (erfApprox((a * len + b) * scale) - erfApprox(b * scale));
}

// Part of [0, len] where px + dx * u is below the edge.
vec2 belowEdge(float px, float dx, float edge, float len) {
    if (abs(dx) < 1.0e-6) return px < edge ? vec2(0.0, len) : vec2(0.0);
    float u = clamp((edge - px) / dx, 0.0, len);
    return dx > 0.0 ? vec2(0.0, u) : vec2(u, len);
}

// Curve parameter with the same beam-axis coordinate as p. The curve's x(t) is quadratic in t.
float curveT(float px) {
    float b = 2.0 * Control.x;
    float a = BeamLen - b;
    float root = sqrt(max(b * b + 4.0 * a * px, 0.0));
    return clamp(2.0 * px / max(b + root, 1.0e-4), 0.0, 1.0);
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
    vec3 cloud = vec3(0.0);
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    float t = t0 + jitter * MIN_SKIP;
    for (int i = 0; i < MAX_STEPS; i++) {
        if (t >= t1 || through < OPAQUE_THROUGH) break;
        vec3 p = CamLocal + dir * t;
        float s = curveT(p.x);
        float bend = 2.0 * s * (1.0 - s);
        vec3 centre = vec3(bend * Control.x + s * s * BeamLen, bend * Control.yz);
        float d = length(p - centre);
        float g = mix(ProfileSpan.x, ProfileSpan.y, s);
        float waist = g - 0.5;
        float r = mix(WaistRadius, EnvelopeRadius, 4.0 * waist * waist);
        float remaining = t1 - t;
        float minStep = remaining / float(MAX_STEPS - i);
        bool inside = d < r;
        float stepLen = min(inside
            ? max(r * INSIDE_STEP, minStep)
            : max(max((d - r) * SKIP_SCALE, MIN_SKIP), minStep), remaining);

        // On every step, also a skip that minStep stretches across the tube. Clipped to the
        // beam span: past the orb centres the tangent line would carry the line on.
        float u0 = 0.0;
        float u1 = stepLen;
        if (abs(dir.x) > 1.0e-6) {
            float ua = -p.x / dir.x;
            float ub = (BeamLen - p.x) / dir.x;
            u0 = max(u0, min(ua, ub));
            u1 = min(u1, max(ua, ub));
        } else if (p.x < 0.0 || p.x > BeamLen) {
            u1 = 0.0;
        }
        float toEnd = 4.0 * waist * waist;
        float haloRadius = mix(HaloRadius, HaloEndRadius, toEnd);
        bool lit = d < haloRadius * GAUSS_REACH + stepLen;
        if (!inside && !lit) {
            t += stepLen;
            continue;
        }
        vec3 around = linkColour(p.yz - centre.yz);
        // The cloud in front of this step hides part of its light.
        if (lit) {
            // Emission × radius stays fixed, so a wider part of the envelope is not brighter from the side.
            float haloEmission = HaloEmission * HaloRadius / haloRadius * mix(1.0, g < 0.5 ? OrbShare.x : OrbShare.y, toEnd);
            float halo = 0.0;
            if (u1 > u0) {
                vec3 tangent = normalize(vec3(
                    (1.0 - s) * Control.x + s * (BeamLen - Control.x),
                    (1.0 - 2.0 * s) * Control.yz));
                halo += gaussIntegral(p + dir * u0 - centre, dir, tangent, u1 - u0, haloRadius);
            }
            // Past an orb centre the envelope is a round cap on that orb.
            vec2 sendCap = belowEdge(p.x, dir.x, 0.0, stepLen);
            vec2 recvCap = belowEdge(-p.x, -dir.x, -BeamLen, stepLen);
            if (sendCap.y > sendCap.x) {
                halo += gaussIntegral(p + dir * sendCap.x, dir, vec3(0.0), sendCap.y - sendCap.x, haloRadius);
            }
            if (recvCap.y > recvCap.x) {
                halo += gaussIntegral(p + dir * recvCap.x - vec3(BeamLen, 0.0, 0.0), dir, vec3(0.0), recvCap.y - recvCap.x, haloRadius);
            }
            emitted += through * around * haloEmission * halo;
        }

        if (inside) {
            float q = 1.0 - (d * d) / (r * r);
            // The glyph count is the same at each t, so a narrower cross-section is denser.
            float stepAlpha = 1.0 - exp(-Density * (WaistRadius / r) * q * q * q * stepLen);
            float inner = 1.0 - d / r;
            vec3 c = around * mix(EdgeBrightness, 1.0, inner * inner);
            cloud += through * stepAlpha * c;
            through *= 1.0 - stepAlpha;
        }
        t += stepLen;
    }
    float alpha = (1.0 - through) * Opacity;
    if (alpha < 0.004 && max(max(emitted.r, emitted.g), emitted.b) < 0.004) discard;
    // Premultiplied output (orb_link_volume.json blend): the cloud covers, the halo adds light.
    fragColor = vec4(cloud * Opacity + emitted, alpha);
}
