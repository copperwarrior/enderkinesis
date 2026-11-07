#version 150

// Look modelled on "Hand-drawn Sketch Effect" by hlorenzi (Shadertoy, CC BY-NC-SA); no code is taken from it.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 SketchSize;
uniform float PixelScale;
uniform vec2 ToneRange;
// Ground contact in sketch UV: x and y of the lowest point, half the base width.
uniform vec4 Base;
// Notes, one column each: NoteA = anchor.xy, text origin.xy; NoteB = width (GUI px), lines, attach side, seed.
uniform mat4 NoteA;
uniform mat4 NoteB;
uniform float NoteCount;
// Zoom, then x and y offset: see SketchFrame.
uniform vec4 Frame;
// 1 for a viewfinder rebuild: Sampler1 alpha flags ground blocks, ground marks run under all their lower edges, and
// lines follow each block's fade, not a half-coverage contour.
uniform float OutlineGround;
// Width of the fade at the left and right of a rebuilt view, in render UV; 0 for none.
uniform float SideFade;
// 1 for a dye wash under the graphite: each block's own colour, snapped to the nearest of the 16 dye colours.
uniform float Colour;
in vec2 texCoord0;
out vec4 fragColor;
const float PI = 3.14159265;
const float SEED = 1.0;
float hash(float x) { return fract(sin(x) * 43758.5453); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float zigzag(float x) { return abs(1.0 - mod(abs(x), 2.0)) * 2.0 - 1.0; }
// Outside the render there is only paper; the clamped sampler would smear edge texels outward.
vec4 colourAt(vec2 uv) {
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
  return texture(Sampler0, uv);
}
float coverage(vec2 uv) { return colourAt(uv).a; }
float tone(vec2 uv) {
  vec4 c = colourAt(uv);
  float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
  float span = max(ToneRange.y - ToneRange.x, 0.05);
  return mix(1.0, clamp((l - ToneRange.x) / span, 0.0, 1.0), c.a);
}
float shade(vec2 uv, float blur) {
  vec2 r = blur * PixelScale / SketchSize;
  float sum = 0.0;
  for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) sum += tone(uv + vec2(float(x), float(y)) * r);
  return sum / 9.0;
}
// The coverage that counts as a block: half for sketches, any for a rebuilt view, where fades are real.
float solid() { return OutlineGround > 0.5 ? 0.02 : 0.5; }
float outline(vec2 uv, float alpha, float width) {
  if (alpha > solid()) return 0.0;
  vec2 radius = max(1.0, width * PixelScale) / SketchSize;
  float near = 0.0;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * PI / 4.0;
    vec2 d = vec2(cos(a), sin(a));
    near = max(near, coverage(uv + d * radius));
    near = max(near, coverage(uv + d * radius * 0.5));
  }
  return OutlineGround > 0.5 ? near : step(0.5, near);
}
float detail(vec2 uv, float alpha, float lo, float hi) {
  vec2 d = PixelScale / SketchSize;
  float t00 = tone(uv + vec2(-d.x, -d.y)), t10 = tone(uv + vec2(0.0, -d.y)), t20 = tone(uv + vec2(d.x, -d.y));
  float t01 = tone(uv + vec2(-d.x, 0.0)), t21 = tone(uv + vec2(d.x, 0.0));
  float t02 = tone(uv + vec2(-d.x, d.y)), t12 = tone(uv + vec2(0.0, d.y)), t22 = tone(uv + vec2(d.x, d.y));
  float gx = (t20 + 2.0 * t21 + t22) - (t00 + 2.0 * t01 + t02);
  float gy = (t02 + 2.0 * t12 + t22) - (t00 + 2.0 * t10 + t20);
  return smoothstep(lo, hi, length(vec2(gx, gy))) * alpha;
}
vec2 wobble(vec2 uv, float seed, float amount) {
  return uv + vec2(zigzag(uv.y * hash(seed * 0.37) * 4.0) * hash(seed * 0.61),
                   zigzag(uv.x * hash(seed * 0.83) * 4.0) * hash(seed * 0.29)) * amount;
}
vec2 grain(vec2 uv, float amount) {
  return uv + (vec2(hash(dot(uv, vec2(12.9, 78.2))), hash(dot(uv, vec2(39.3, 11.1)))) - 0.5) * amount;
}

const vec3 INK = vec3(0.27, 0.27, 0.29);
vec2 frame(vec2 u) { return 0.5 + (u - 0.5) * Frame.x + Frame.yz; }
float sparseMarks(vec2 uv, float alpha) {
  vec2 cell = floor(texCoord0 * SketchSize / (PixelScale * 3.0));
  return detail(uv, alpha, 1.3, 2.2) * 0.4 * step(0.25, hash2(cell + 7.0));
}
float tooth() { return 0.55 + 0.45 * hash2(floor(texCoord0 * SketchSize)); }
float hatchLines(vec2 uv, vec2 dir, float period, float width) {
  vec2 px = (uv - 0.5) * SketchSize / PixelScale;
  float d = abs(fract(dot(px, vec2(-dir.y, dir.x)) / period) - 0.5) * period;
  float aa = 0.5 / PixelScale;
  return 1.0 - smoothstep(width - aa, width + aa, d);
}
vec3 normalAt(vec2 uv) { return texture(Sampler1, uv).xyz * 2.0 - 1.0; }
float sideDark(vec2 uv, float radius) {
  vec2 r = radius * PixelScale / SketchSize;
  float most = 0.0;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * PI / 4.0;
    vec2 q = uv + vec2(cos(a), sin(a)) * r;
    if (coverage(q) > 0.5) most = max(most, 1.0 - shade(q, 0.4));
  }
  return most;
}
float innerEdge(vec2 uv, float width) {
  if (coverage(uv) < solid()) return 0.0;
  vec2 r = max(1.0, width * PixelScale) / SketchSize;
  vec3 n = normalAt(uv);
  float bend = 0.0;
  for (int i = 0; i < 4; i++) {
    float a = float(i) * PI / 2.0;
    vec2 q = uv + vec2(cos(a), sin(a)) * r;
    if (coverage(q) > solid()) bend = max(bend, 1.0 - dot(n, normalAt(q)));
  }
  return smoothstep(0.2, 0.5, bend) * (OutlineGround > 0.5 ? coverage(uv) : 1.0);
}
float groundAt(vec2 uv) { return coverage(uv) * step(0.75, texture(Sampler1, uv).a); }
// Paper with the lower edge of a ground block just above it, so a terrain chunk meets the page on all its sides.
float underGround(vec2 uv) {
  for (int i = 1; i <= 8; i++) {
    float g = groundAt(uv + vec2(0.0, 0.08 * float(i) / 8.0));
    if (g > 0.02) return g;
  }
  return 0.0;
}
float groundMask(vec2 uv, float alpha) {
  if (OutlineGround > 0.5) return step(alpha, solid()) * underGround(uv);
  float top = Base.y + Base.z * 0.5;
  return (1.0 - alpha) * (1.0 - smoothstep(top, top + 0.06, uv.y));
}
float castShadow(vec2 uv, float alpha, float t) {
  float reach = 0.0;
  for (int i = 1; i <= 8; i++) {
    float k = float(i) / 8.0;
    if (coverage(uv - vec2(0.2, -0.06) * k) > 0.5) { reach = 1.0 - k * 0.85; break; }
  }
  float lines = hatchLines(uv, normalize(vec2(1.0, 0.25)), 1.9, 0.38);
  return groundMask(uv, alpha) * reach * mix(lines * 0.55, 0.7, smoothstep(0.75, 0.95, reach)) * t;
}
float contactShadow(vec2 uv, float alpha, float t) {
  float near = 0.0;
  for (int i = 1; i <= 4; i++) {
    float k = float(i) / 4.0;
    if (coverage(uv + vec2(-0.01, 0.025) * k) > 0.5) { near = 1.0 - k * 0.7; break; }
  }
  return groundMask(uv, alpha) * near * 0.75 * t;
}
float overshoot(vec2 uv, float alpha) {
  vec2 b = Base.xy;
  float tc = Base.z / 0.894;
  float w = 0.35 * PixelScale / SketchSize.x;
  float aa = 0.5 / SketchSize.x;
  float ink = 0.0;
  for (int i = 0; i < 2; i++) {
    vec2 d = normalize(vec2(i == 0 ? -1.0 : 1.0, 0.5));
    vec2 q = uv - b;
    float along = dot(q, d);
    float off = abs(dot(q, vec2(-d.y, d.x)));
    float line = 1.0 - smoothstep(w, w + aa, off);
    float span = max(step(tc * 0.95, along) * (1.0 - smoothstep(tc * 1.05, tc * 1.45, along)),
                     step(-tc * 0.3, along) * step(along, 0.0) * (1.0 - smoothstep(0.0, tc * 0.3, -along)));
    ink = max(ink, line * span);
  }
  return ink * 0.6 * (1.0 - alpha);
}
vec2 guiPx(vec2 d) { return d * SketchSize / PixelScale; }

// The drawing fades out evenly toward the left and right edges of a rebuilt view.
float sideFade(vec2 uv) {
  if (SideFade <= 0.0) return 1.0;
  return smoothstep(0.0, SideFade, min(uv.x, 1.0 - uv.x));
}

// Loops, not glyphs, so the notes can never be read.
float scribble(vec2 p, vec2 origin, float width, float lines, float seed, bool heading) {
  vec2 q = guiPx(p - origin); q.y = -q.y;
  float aa = 0.5 / PixelScale;
  float ink = 0.0;
  for (int i = 0; i < 5; i++) {
    float fi = float(i);
    if (fi >= lines) break;
    float baseY = 3.6 + fi * 5.0;
    float lineW = fi == lines - 1.0 ? width * (0.4 + 0.45 * hash(seed + fi * 7.0)) : width * (0.85 + 0.15 * hash(seed + fi));
    float x = q.x + (q.y - baseY) * 0.3;
    if (x < 0.0 || x > lineW || abs(q.y - baseY + 1.2) > 3.6) continue;
    float shift = hash(seed * 3.1 + fi) * 8.0;
    float word = floor((x + shift) / 8.0);
    float inWord = mod(x + shift, 8.0);
    if (inWord > 5.4 + hash(word * 1.3 + seed + fi * 5.0) * 2.0) continue;
    float k = 2.0 * PI / 1.55;
    float letter = floor(x / 1.55);
    float amp = (heading && i == 0 ? 1.3 : 1.0) * 1.2 * (0.65 + 0.5 * hash(letter * 1.7 + seed + fi * 13.0));
    float tall = step(0.8, hash(letter * 2.3 + seed + fi)) * 1.5;
    float ph = hash(seed + fi) * 6.0;
    float y = -amp * (0.5 + 0.5 * sin(x * k + ph)) - tall * max(0.0, sin(x * k));
    float dy = -amp * 0.5 * k * cos(x * k + ph) - tall * k * cos(x * k) * step(0.0, sin(x * k));
    float dist = abs(q.y - (baseY + y)) / sqrt(1.0 + dy * dy);
    ink = max(ink, 1.0 - smoothstep(0.24, 0.24 + aa, dist));
  }
  if (heading) {
    float x = q.x;
    float w = width * (0.85 + 0.15 * hash(seed));
    float under = abs(q.y - (4.6 + 0.15 * sin(x * 0.7 + seed)));
    ink = max(ink, (1.0 - smoothstep(0.18, 0.18 + aa, under)) * step(0.0, x) * step(x, w) * 0.8);
  }
  return ink;
}
float segment(vec2 p, vec2 a, vec2 b) {
  vec2 ab = b - a;
  float h = clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0);
  return length(p - a - ab * h);
}
float leader(vec2 p, vec2 anchor, vec2 tip, float bow, bool arrow, float seed) {
  vec2 a = guiPx(anchor), b = guiPx(tip), q = guiPx(p);
  vec2 dir = normalize(b - a);
  vec2 perp = vec2(-dir.y, dir.x);
  float aa = 0.5 / PixelScale;
  float d = 1e9;
  vec2 prev = a;
  for (int i = 1; i <= 10; i++) {
    float t = float(i) / 10.0;
    vec2 pt = mix(a, b, t) + perp * (bow * sin(t * PI) + 0.25 * sin(t * 13.0 + seed * 6.0));
    d = min(d, segment(q, prev, pt));
    prev = pt;
  }
  float ink = 1.0 - smoothstep(0.26, 0.26 + aa, d);
  if (arrow) {
    vec2 start = normalize(mix(a, b, 0.1) + perp * bow * sin(0.1 * PI) - a);
    for (int s = -1; s <= 1; s += 2) {
      float ang = float(s) * 0.45;
      vec2 wing = vec2(start.x * cos(ang) - start.y * sin(ang), start.x * sin(ang) + start.y * cos(ang));
      ink = max(ink, 1.0 - smoothstep(0.3, 0.3 + aa, segment(q, a, a + wing * 3.4)));
    }
  } else {
    ink = max(ink, 1.0 - smoothstep(0.95, 0.95 + aa, length(q - a)));
  }
  return ink;
}
// NoteB.z: the sign is the attach side; |z| - 1 holds style bits (1 arrow, 2 curved, 4 underlined).
float notes(vec2 p) {
  float ink = 0.0;
  for (int i = 0; i < 4; i++) {
    if (float(i) >= NoteCount) break;
    vec4 at = NoteA[i];
    vec4 info = NoteB[i];
    float seed = info.w;
    int bits = int(abs(info.z) + 0.5) - 1;
    bool arrow = (bits & 1) != 0;
    float bow = (bits & 2) != 0 ? 2.5 : 0.0;
    bool heading = (bits & 4) != 0;
    vec2 origin = at.zw;
    float lineH = 3.0 * PixelScale / SketchSize.y;
    float gap = 1.2 * PixelScale / SketchSize.x;
    vec2 tip = info.z > 0.0 ? origin + vec2(-gap, -lineH) : origin + vec2(info.x * PixelScale / SketchSize.x + gap, -lineH);
    float b = bow * (hash(seed * 1.9) > 0.5 ? 1.0 : -1.0);
    ink = max(ink, leader(p, at.xy, tip, b, arrow, seed));
    ink = max(ink, scribble(p, origin, info.x, info.y, seed, heading));
  }
  return ink;
}
float vnoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash2(i), hash2(i + vec2(1.0, 0.0)), f.x), mix(hash2(i + vec2(0.0, 1.0)), hash2(i + 1.0), f.x), f.y);
}
// Lighting.setupFor3DItems in view space. The colour pass is lit only by these, so dividing them out gives the albedo.
const vec3 ITEM_LIGHT0 = vec3(-0.22252, 0.17150, 0.95973);
const vec3 ITEM_LIGHT1 = vec3(-0.21501, 0.97183, 0.09657);
float itemLight(vec2 uv) {
  vec3 n = normalize(normalAt(uv));
  return min(1.0, (max(0.0, dot(ITEM_LIGHT0, n)) + max(0.0, dot(ITEM_LIGHT1, n))) * 0.6 + 0.4);
}
// Within r GUI px: the most coverage (a reach past small forms such as candles), and the mean albedo of covered pixels.
float washReach(vec2 uv, float r) {
  vec2 d = r * PixelScale / SketchSize / 3.0;
  float most = 0.0;
  for (int y = -3; y <= 3; y++) for (int x = -3; x <= 3; x++) {
    float k = length(vec2(x, y)) / 3.0;
    if (k > 1.0) continue;
    most = max(most, coverage(uv + vec2(float(x), float(y)) * d) * (1.0 - 0.6 * k * k));
  }
  return most;
}
vec3 nearAlbedo(vec2 uv, float r) {
  vec2 d = r * PixelScale / SketchSize / 3.0;
  vec4 s = vec4(0.0);
  for (int y = -3; y <= 3; y++) for (int x = -3; x <= 3; x++) {
    if (length(vec2(x, y)) > 3.0) continue;
    vec2 p = uv + vec2(float(x), float(y)) * d;
    vec4 c = colourAt(p);
    if (c.a < 0.5) continue;
    s += vec4(c.rgb / itemLight(p), 1.0);
  }
  return s.a > 0.0 ? s.rgb / s.a : vec3(1.0);
}
// DyeColor texture colours, white to black.
const vec3 DYES[16] = vec3[16](
  vec3(0.976, 1.0, 0.996), vec3(0.976, 0.502, 0.114), vec3(0.78, 0.306, 0.741), vec3(0.227, 0.702, 0.855),
  vec3(0.996, 0.847, 0.239), vec3(0.502, 0.78, 0.122), vec3(0.953, 0.545, 0.667), vec3(0.278, 0.31, 0.322),
  vec3(0.616, 0.616, 0.592), vec3(0.086, 0.612, 0.612), vec3(0.537, 0.196, 0.722), vec3(0.235, 0.267, 0.667),
  vec3(0.514, 0.329, 0.196), vec3(0.369, 0.486, 0.086), vec3(0.69, 0.18, 0.149), vec3(0.114, 0.114, 0.129));
// Painter's wells, 64 colours: each dye darker, as is, lighter and muted (greyed halfway to its own value).
// Muted neutrals and lighter white repeat other wells, so 5 mixed wells take their places.
const vec3 LUMA = vec3(0.299, 0.587, 0.114);
const vec3 MIXES[5] = vec3[5](
  vec3(0.329, 0.490, 0.390), vec3(0.187, 0.334, 0.243), vec3(0.442, 0.407, 0.141), vec3(0.755, 0.588, 0.217),
  vec3(0.270, 0.293, 0.437));
vec3 well(vec3 dye, int j) {
  if (j == 0) return dye * 0.65;
  if (j == 2) return mix(dye, vec3(1.0), 0.35);
  if (j == 3) return mix(dye, vec3(dot(dye, LUMA)), 0.5);
  return dye;
}
bool repeats(int i, int j) { return (j == 3 && (i == 0 || i == 7 || i == 8 || i == 15)) || (i == 0 && j == 2); }
// Hue and chroma count more than lightness.
float dyeDistance(vec3 c, vec3 p) {
  vec3 diff = c - p;
  float dl = dot(diff, LUMA);
  vec3 dc = diff - dl;
  return dot(dc, dc) + dl * dl * 0.35;
}
vec3 nearestDye(vec3 c) {
  vec3 best = DYES[0];
  float bestD = 1e9;
  for (int i = 0; i < 16; i++) for (int j = 0; j < 4; j++) {
    if (repeats(i, j)) continue;
    vec3 p = well(DYES[i], j);
    float dist = dyeDistance(c, p);
    if (dist < bestD) { bestD = dist; best = p; }
  }
  for (int k = 0; k < 5; k++) {
    float dist = dyeDistance(c, MIXES[k]);
    if (dist < bestD) { bestD = dist; best = MIXES[k]; }
  }
  return best;
}
// Set a little off the line and a little past each form; darker where it dried; bare paper on the brightest tops.
vec4 dyeWash(float ink, vec2 uv) {
  vec2 g = texCoord0 * SketchSize / PixelScale;
  vec2 wuv = frame(wobble(texCoord0, SEED + 31.0, 0.012) + vec2(0.004, -0.003));
  float reach = washReach(wuv, 2.4) * (0.75 + 0.5 * vnoise(g / 3.5));
  float body = smoothstep(0.35, 0.55, reach);
  float rim = smoothstep(0.3, 0.45, reach) * (1.0 - smoothstep(0.5, 0.75, reach));
  float leave = smoothstep(0.88, 0.98, tone(wuv)) * smoothstep(0.4, 0.7, vnoise(g / 6.0 + 13.0));
  float depth = mix(0.55, 0.85, 1.0 - tone(wuv));
  float a = (body * depth * (0.8 + 0.2 * vnoise(g / 9.0)) + rim * 0.3) * (1.0 - leave * 0.85) * sideFade(uv);
  vec3 c = nearestDye(nearAlbedo(wuv, 2.4)) * mix(1.0, itemLight(wuv), 0.5) * (1.0 - rim * 0.25);
  float outA = 1.0 - (1.0 - a) * (1.0 - ink);
  return outA < 0.001 ? vec4(0.0) : vec4((c * a * (1.0 - ink) + INK * ink) / outA, outA);
}

void main() {
  vec2 uv = frame(grain(wobble(texCoord0, SEED + 5.0, 0.004), 0.002));
  float alpha = coverage(uv);
  float raw = smoothstep(0.1, 0.9, 1.0 - shade(uv, 0.4));
  float family = smoothstep(0.3, 0.42, raw) * 0.5 + smoothstep(0.62, 0.74, raw) * 0.3;
  float halftone = smoothstep(0.2, 0.4, raw) * (1.0 - smoothstep(0.62, 0.85, raw));
  float nearEdge = max(innerEdge(uv, 4.0), outline(uv, 1.0 - alpha, 4.0));
  // Strong texture contrast (book spines, carvings) keeps its detail in any value family.
  float salient = smoothstep(0.2, 0.45, abs(tone(uv) - shade(uv, 2.5)));
  float interest = clamp(halftone * 0.5 + nearEdge + salient, 0.0, 1.0);
  float t = tooth();
  float smudge = mix(family, raw * 0.8, mix(0.05, 0.9, interest)) * t * alpha;
  // The S-curve drops the lightest tones; salient detail uses plain tone so thin lines in light areas stay.
  smudge = max(smudge, (1.0 - tone(uv)) * 0.6 * salient * t * alpha);
  float lines = hatchLines(uv, normalize(vec2(0.5, 0.866)), 2.6, 0.25) * smoothstep(0.45, 0.75, raw) * 0.55 * alpha * t;
  float groundInk = max(max(castShadow(uv, alpha, t), contactShadow(uv, alpha, t)), overshoot(uv, alpha));
  vec2 uvA = frame(wobble(texCoord0, SEED + 11.0, 0.009));
  vec2 uvB = frame(wobble(texCoord0, SEED + 17.0, 0.009));
  float foundA = smoothstep(0.15, 0.6, sideDark(uvA, 2.0));
  float foundB = smoothstep(0.15, 0.6, sideDark(uvB, 2.0));
  float edges = max(outline(uvA, coverage(uvA), mix(0.4, 0.95, foundA)) * mix(0.12, 0.9, foundA),
                    outline(uvB, coverage(uvB), 0.45) * 0.45 * foundB);
  float iA = innerEdge(uvA, 0.55) * mix(0.15, 0.85, foundA);
  float iB = innerEdge(uvB, 0.45) * 0.4 * foundB;
  edges = max(edges, max(iA, iB));
  float marks = sparseMarks(uv, alpha) * mix(0.1, 1.5, interest);
  // Gentle edges in light areas (book spines, ring lines) get light marks; dark areas stay one mass.
  float keep = step(0.25, hash2(floor(texCoord0 * SketchSize / (PixelScale * 3.0)) + 7.0));
  marks = max(marks, detail(uv, alpha, 0.4, 0.9) * 0.35 * (1.0 - smoothstep(0.3, 0.6, raw)) * keep);
  // Under a wash the graphite tone is a light underdrawing; the wash carries the values.
  float tonal = Colour > 0.5 ? 0.3 : 1.0;
  float ink = max(max(max(smudge * tonal, lines * tonal), groundInk), max(edges, marks)) * sideFade(uv);
  vec2 np = wobble(texCoord0, SEED + 23.0, 0.002);
  ink = max(ink, notes(np) * mix(0.85 * t, 1.0, alpha));
  fragColor = Colour > 0.5 ? dyeWash(ink, uv) : vec4(INK, ink);
  if (fragColor.a < 0.02) discard;
}
