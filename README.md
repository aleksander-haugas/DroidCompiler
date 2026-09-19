# DroidCompiler v0.6.0 — Runtime Suite

This version keeps the working targetSdk 36 architecture and expands the runtime validation layer.

## Working pipeline

`main.cpp -> embedded Clang 21 -> main.o -> LLD -> libprogram.so -> RUN`

- Console programs run in `:runner` via `dlopen()` + `main`.
- SDL2/OpenGL ES programs run in `:sdlrunner` via `SDLActivity` + `SDL_main`.
- SDL2 demo now creates a real GLES 3 shader program, VBO/VAO and reacts to `SDL_FINGERDOWN`, `SDL_FINGERMOTION` and `SDL_FINGERUP`.
- Network demo performs HTTPS with libcurl, raw TCP, UDP DNS and a WSS echo using libcurl's WebSocket API.
- Runner processes receive PREFIX/HOME/TMPDIR and CA-certificate environment variables before user code starts.

## Orientation

The project now owns `project.properties` with `orientation=landscape` as the default. The runtime already reads this setting. The editor UI for portrait/landscape/sensor/auto is intentionally deferred to the Project Settings phase.

## Test

1. Install/open the app.
2. `INSTALL TOOLCHAIN DATA` if needed.
3. `LOAD GLES3 + TOUCH DEMO` -> BUILD -> RUN. Drag a finger: the GLES triangle should follow the touch position and change color.
4. Back to editor.
5. `LOAD NETWORK DEMO` -> BUILD -> RUN. Console reports HTTPS/TCP/UDP/WebSocket results.
