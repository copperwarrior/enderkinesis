#version 150

// Staff of Concealment — true smooth-fade refraction with PER-PIXEL progress.
//
// Architecture:
//   * Main FB (DiffuseSampler)  → world WITHOUT ship (concealment is on full-time).
//   * Ship FB (ShipSampler)     → ship-only render from a separate pass that drew
//                                  every cloaking ship's solid blocks to a side FB.
//   * Mask FB (MaskSampler)     → RG8: R = per-pixel cloak progress, G = bubble
//                                  silhouette indicator. Multi-ship: each ship's
//                                  bubble is drawn with `glBlendEquation(GL_MAX)`
//                                  so overlapping bubbles take the dominant
//                                  cloak's progress, and `G` saturates to 1 if
//                                  any bubble covers the pixel.
//
// Per fragment inside any bubble:
//   1. Sample MaskSampler RG with a 9-tap blur (~6 UV-px) → soft silhouette.
//   2. Recover the per-pixel cloak progress by dividing the blurred R by the
//      blurred G — this undoes the kernel's averaging across edge transitions
//      and returns the original progress that the bubble pass wrote for the
//      cloak covering this pixel (or the dominant if multiple).
//   3. Compute a small radial sin-ripple displacement around `ShipCenterUV`
//      (a random screen corner picked at engagement). Amplitude + frequency
//      ease in from 0 via `pow(localProgress, 2.5)` — per-pixel.
//   4. Sample ShipSampler at displaced UV → "refracted ship pixel."
//   5. Sample DiffuseSampler at raw UV → background (ship was concealed).
//   6. Output = mix(background, refractedShip, silhouette * shipAlpha * fade)
//      where fade = 1 - localProgress. Per-pixel, so each ship's bubble
//      composites at its own progress — no global cloak value forces every
//      bubble to track the dominant one.
//
// Outside every bubble (mask G ≤ 0.001) → pass through DiffuseSampler unchanged.

uniform sampler2D DiffuseSampler;
uniform sampler2D MaskSampler;
uniform sampler2D ShipSampler;
uniform float TimeSec;
// Legacy single-cloak progress. Kept for the uniform contract but unused by
// the shader now that the mask encodes per-pixel progress. Safe to leave
// being pushed each frame from the Kotlin side.
uniform float CloakProgress;
uniform vec2 ShipCenterUV;
// Highest progress of any bubble the camera is physically inside; 0 if the
// observer is outside every cloak bubble. Drives the inside-observer mode:
//   - cloaked content (side FB) shown crisply on top
//   - outside world (main FB) gets a light warbly distortion
// Same `pow(progress, 2.5)` ramp-up as the outside-mode effect.
uniform float InsideBubbleProgress;

in vec2 texCoord;
out vec4 fragColor;

/** 9-tap box blur of the mask RG. Soft silhouette edge + smooth per-pixel
 *  progress transitions between adjacent bubble regions. */
vec2 maskBlurred(vec2 uv) {
    const float R = 0.006;
    vec2 m = vec2(0.0);
    m += texture(MaskSampler, uv).rg                          * 0.25;
    m += texture(MaskSampler, uv + vec2( R,   0.0)).rg        * 0.10;
    m += texture(MaskSampler, uv + vec2(-R,   0.0)).rg        * 0.10;
    m += texture(MaskSampler, uv + vec2( 0.0,  R)).rg         * 0.10;
    m += texture(MaskSampler, uv + vec2( 0.0, -R)).rg         * 0.10;
    m += texture(MaskSampler, uv + vec2( R,   R)).rg          * 0.0875;
    m += texture(MaskSampler, uv + vec2( R,  -R)).rg          * 0.0875;
    m += texture(MaskSampler, uv + vec2(-R,   R)).rg          * 0.0875;
    m += texture(MaskSampler, uv + vec2(-R,  -R)).rg          * 0.0875;
    return m;
}

void main() {
    vec2 maskRG = maskBlurred(texCoord);
    float blurredProgress   = maskRG.r;
    float blurredSilhouette = maskRG.g;

    // --- INSIDE OBSERVER MODE --------------------------------------------
    // Camera is physically inside a cloak bubble. The cancel + redirect
    // mixins have already arranged for THAT ship to render normally to the
    // main FB (no side-FB redirect, no main-FB cancel — see
    // ShipBatchRendererCloakingMixin and CloakingMixinSupport.insideBubbles).
    // So main FB at this point holds: the cloaked-but-camera-inside ship,
    // its dragged entities, its particles, AND the world outside the bubble.
    // Hand items, non-cloaked entities, terrain are all there too at their
    // correct depths.
    //
    // The bubble silhouette (mask G channel) discriminates WHERE the warble
    // applies. With the bubble pass's depth-test + write:
    //   - At ship pixels (main FB depth < bubble back-face depth): bubble
    //     LEQUAL fails → G = 0 → no warble. Ship stays crisp.
    //   - At pixels where the world BEYOND the bubble back-face is visible:
    //     bubble passes → G > 0 → warble applied.
    // The side FB still carries OTHER cloaked ships (whose bubbles the camera
    // is outside) — those composite on top of the (warbled or not) main FB.
    if (InsideBubbleProgress > 0.0) {
        float ease = pow(InsideBubbleProgress, 2.5);
        float ampW  = 0.008 * ease;     // 0 at engagement → ~0.008 fully cloaked
        float freqW = 18.0 * ease;
        float t = TimeSec * 1.6;
        vec2 wobble = vec2(
            sin(texCoord.y * freqW          + t       ) * cos(texCoord.x * freqW * 0.7 + t * 0.8),
            cos(texCoord.x * freqW * 0.85   + t * 1.1 ) * sin(texCoord.y * freqW * 0.6 + t * 0.9)
        ) * ampW;

        // Warble factor follows the bubble silhouette mask. Inside the
        // bubble (G ≈ 0) → no displacement, main FB shows crisply through.
        // Outside the bubble (G > 0) → warble proportional to G (gives a
        // soft fade at the edge from the 9-tap blur).
        vec2 worldUv = clamp(texCoord + wobble * blurredSilhouette, vec2(0.001), vec2(0.999));
        vec3 worldRGB = texture(DiffuseSampler, worldUv).rgb;

        // Composite side FB content (other cloaked ships the camera is
        // outside of) crisply on top.
        vec4 shipSample = texture(ShipSampler, texCoord);
        fragColor = vec4(mix(worldRGB, shipSample.rgb, shipSample.a), 1.0);
        return;
    }

    // --- OUTSIDE OBSERVER MODE -------------------------------------------
    if (blurredSilhouette <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    // Recover the original per-pixel progress. The bubble pass writes
    // `(progress, 1.0)` per ship. After blur, R is "average progress weighted
    // by silhouette coverage" and G is "average silhouette coverage." The
    // ratio cancels the coverage weighting and returns just the progress —
    // which lets a low-progress bubble stay low even right next to a
    // high-progress bubble whose blurred edge bleeds into it.
    float localProgress = clamp(blurredProgress / max(blurredSilhouette, 0.001), 0.0, 1.0);

    // Radial direction from the ripple origin (random screen corner picked at
    // engagement). Concentric sin-ripple emanating outward over time. BOTH
    // frequency and amplitude ramp from 0 at progress=0 so the engagement
    // frame is a clean pass-through (no ripple, no amp), then ease in to half
    // the previous lake-ripple max. Speed is constant so the wavefronts stay
    // coherent through the ramp.
    vec2 toPixel = texCoord - ShipCenterUV;
    float dist = length(toPixel) + 0.0001;
    vec2 radial = toPixel / dist;
    float ease = pow(localProgress, 2.5);
    float freq = 16.0 * ease;     // 0 at start → 16 at full cloak
    float amp  = 0.0175 * ease;   // 0 at start → 0.0175 at full cloak
    const float SPEED = 6.0;      // wavefront travel; higher = faster ripple
    float ripple = sin(dist * freq - TimeSec * SPEED);
    vec2 displacement = radial * ripple * amp;
    vec2 sampleUv = clamp(texCoord + displacement, vec2(0.001), vec2(0.999));

    // Ship FB is RGBA — alpha=1 where ship pixels are, 0 where transparent
    // (sky behind ship). The ship's own alpha + the cloak fade combine into
    // the mix factor.
    vec4 shipSample = texture(ShipSampler, sampleUv);
    vec3 background = texture(DiffuseSampler, texCoord).rgb;

    // alpha curve: 1 at progress=0 (ship fully visible as refraction), 0 at
    // progress=1 (background visible, ship gone). Per-pixel.
    float fade = 1.0 - localProgress;

    // Mix factor combines:
    //   - bubble silhouette (`blurredSilhouette`, blurred-edge fade)
    //   - ship's own alpha at the sampled UV (so sampling past the ship's
    //     edge reveals background, not garbage)
    //   - per-pixel cloak fade
    float mixFactor = blurredSilhouette * shipSample.a * fade;

    fragColor = vec4(mix(background, shipSample.rgb, mixFactor), 1.0);
}
