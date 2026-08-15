#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 iportal_ClippingEquation;

out vec4 vertexColor;

void main() {
    vec4 eyePosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * eyePosition;
    gl_ClipDistance[0] = dot(eyePosition.xyz, iportal_ClippingEquation.xyz) +
        iportal_ClippingEquation.w;

    vertexColor = Color;
}
