#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 texProj0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // Inline of vanilla's `projection_from_position` — drives the per-layer texture
    // sampling so the visual matches the end portal block exactly.
    vec4 projection = gl_Position * 0.5;
    projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = gl_Position.zw;
    texProj0 = projection;
}
