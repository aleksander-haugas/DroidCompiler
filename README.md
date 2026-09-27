# DroidCompiler 1.6.9 Clean Base

DroidCompiler is an Android C/C++ IDE that embeds its own Clang/LLD toolchain and can compile and run native projects directly on-device.

## Current feature set

- C/C++ editor with line numbers, syntax highlighting, open-file tabs and font zoom.
- Project workspace management for local and Storage Access Framework linked folders.
- Tree-based file explorer with expandable folders, filtering, create/import/rename/delete actions and active-file highlighting.
- Internal Clang/LLD build pipeline with C4droid-style Makefile compatibility.
- Console and SDL2/OpenGL ES runners.
- Build options and optional native runtime packages.
- Android/JNI host bridge and foreground-run support.
- Build/runtime log and compact output panel.
- Standalone APK export pipeline with per-project metadata, signing, verification, install/save/share flow.

## Source layout

```text
app/                         Main Android IDE application
  src/main/java/com/droidx/  IDE, compiler, runner, project and exporter code
  src/main/cpp/              Native runner bridge
  src/main/res/              App resources
buildSrc/                    Build-time toolchain/SDL/header/export-template embedders
export-runtime/              Source for the runtime embedded in exported APKs
gradle/                      Gradle wrapper configuration
```

`export-runtime/` is build input, not a second installable Gradle application module.

## Build requirements

- Android Studio / Android Gradle Plugin 8.10.1 compatible environment.
- Android SDK platform 36.
- Android NDK installed through SDK Manager or configured with `droidxNdkDir`.
- Supported compiler ABIs: `arm64-v8a` and `x86_64`.

The source archive intentionally does not contain generated Gradle/Android Studio build directories.

## Runtime/project data

DroidCompiler stores its own build state under each project’s `.droidx/` directory. A project-owned directory named `build/` is treated as normal project content and is not repurposed by the IDE.

## Documentation

- `ARCHITECTURE.md` — workspace, build and export architecture.
- `APK_EXPORT.md` — APK exporter behavior and current limitations.
- `CLEAN_BASE.md` — what was removed when this baseline was prepared.
