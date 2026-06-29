#version 150

// Overlay-sphere fragment shader. Sits on top of the fractal sphere (a
// rotated mesh emitted in the prior solid pass) and composites a
// translucent yellow-to-white fresnel glass shell + the world-fixed
// holy-number highlight. This sphere is NOT rotated with the camera —
// the silhouette is its real 3D projection, and the highlight stays
// pinned to its real-world bearing while the fractal underneath spins
// to face the player.

in vec3 vViewNormal;
in vec3 vViewPos;
in vec3 vObjNormal;

out vec4 fragColor;

void main() {
    vec3 N = normalize(vViewNormal);
    vec3 V = normalize(-vViewPos);
    float NdotV = max(dot(N, V), 0.0);
    float fresnel = pow(1.0 - NdotV, 1.4);

    const vec3 SHELL_WHITE  = vec3(1.0);
    const vec3 SHELL_YELLOW = vec3(1.0, 0.88, 0.40);
    vec3 shell = mix(SHELL_WHITE, SHELL_YELLOW, fresnel);
    // ~5% opaque face-on (fractal shows through cleanly) → ~85% opaque
    // at silhouette (yellow glass rim).
    float alpha = mix(0.05, 0.85, fresnel);

    // Holy-number highlight at pitch 34.5° / yaw 34.5° (Sselith holy
    // number, MC entity-orientation convention) — anchored to the
    // object-space normal so the highlight stays at a real-world bearing.
    const float HIGHLIGHT_PITCH = 34.5;
    const float HIGHLIGHT_YAW   = 34.5;
    float pitchR = radians(HIGHLIGHT_PITCH);
    float yawR   = radians(HIGHLIGHT_YAW);
    vec3 highlightDir = vec3(
        -sin(yawR) * cos(pitchR),
        -sin(pitchR),
         cos(yawR) * cos(pitchR)
    );
    vec3 N_obj = normalize(vObjNormal);
    float angularDist = 1.0 - dot(N_obj, highlightDir);
    float highlight = exp(-angularDist * 40.0);
    shell += vec3(1.0, 0.92, 0.50) * highlight * 1.6;
    // Boost alpha so the highlight reads clearly through the otherwise
    // mostly-transparent centre of the shell.
    alpha = max(alpha, highlight);

    fragColor = vec4(clamp(shell, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
}
