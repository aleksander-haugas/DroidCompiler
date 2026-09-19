# DroidCompiler v0.6.1 — Runtime Suite

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

## v0.6.2 dual-ABI fix

The APK now embeds the compiler/network runtime for both common 64-bit Android ABIs by default:

```properties
droidxAbis=x86_64,arm64-v8a
```

`prepareEmbeddedToolchain` also declares this ABI list as a Gradle task input, so switching ABI configuration can no longer be silently skipped as UP-TO-DATE.

- `x86_64`: Android Studio emulator
- `arm64-v8a`: modern physical Android phones

On a physical phone, Clang is not downloaded as executable code at runtime. `libdroidx_clang.so` and `libdroidx_lld.so` must already be packaged for the phone ABI inside the APK. The INSTALL TOOLCHAIN DATA button installs headers, sysroot development data, SDL2/GLES headers, curl headers and certificates only.
