// Vanilla rendertype_end_portal layers. Import after the Sampler0, Sampler1 and GameTime uniforms.

const int END_PORTAL_LAYERS = 15;

const vec3 COLORS[16] = vec3[](
    vec3(0.022087, 0.098399, 0.110818),
    vec3(0.011892, 0.095924, 0.089485),
    vec3(0.027636, 0.101689, 0.100326),
    vec3(0.046564, 0.109883, 0.114838),
    vec3(0.064901, 0.117696, 0.097189),
    vec3(0.063761, 0.086895, 0.123646),
    vec3(0.084817, 0.111994, 0.166380),
    vec3(0.097489, 0.154120, 0.091064),
    vec3(0.106152, 0.131144, 0.195191),
    vec3(0.097721, 0.110188, 0.187229),
    vec3(0.133516, 0.138278, 0.148582),
    vec3(0.070006, 0.243332, 0.235792),
    vec3(0.196766, 0.142899, 0.214696),
    vec3(0.047281, 0.315338, 0.321970),
    vec3(0.204675, 0.390010, 0.302066),
    vec3(0.080955, 0.314821, 0.661491)
);

const mat4 SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

mat2 endPortalRotateZ(float a) {
    return mat2(cos(a), -sin(a), sin(a), cos(a));
}

mat4 endPortalLayer(float n) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 17.0 / n,
        0.0, 1.0, 0.0, (2.0 + n / 1.5) * (GameTime * 1.5),
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );
    mat2 rotate = endPortalRotateZ(radians((n * n * 4321.0 + n * 9.0) * 2.0));
    mat2 scale = mat2((4.5 - n / 4.0) * 2.0);
    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
}

// Sampler0 is end_sky.png, Sampler1 is end_portal.png.
vec3 endPortalColor(vec4 texProj) {
    vec3 color = textureProj(Sampler0, texProj).rgb * COLORS[0];
    for (int i = 0; i < END_PORTAL_LAYERS; i++) {
        color += textureProj(Sampler1, texProj * endPortalLayer(float(i + 1))).rgb * COLORS[i];
    }
    return color;
}
