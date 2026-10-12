#version 150

// Afterburner: the floor under the far terrain's water (see far_water_floor.vsh). Depth only: it writes no color.

uniform mat4 ModelViewMat;
uniform mat4 DhProjection;

in vec3 floorPos;

// A flat floor this many blocks under the water's top; looking across the water, at most this far along the look.
const float FLOOR_DEPTH = 16.0, MAX_RUN = 128.0;

void main() {
    vec3 dir = normalize(floorPos);
    float run = min(FLOOR_DEPTH / max(-dir.y, 1.0e-3), MAX_RUN);
    vec4 clip = DhProjection * (ModelViewMat * vec4(floorPos + dir * run, 1.0));
    // OpenGL's depth, as the pack's far terrain has it.
    gl_FragDepth = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
}
