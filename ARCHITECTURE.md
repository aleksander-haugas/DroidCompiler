# DroidCompiler architecture

## Project workspaces

Android Storage Access Framework folders are exposed as `content://` trees rather than stable native filesystem paths. Linked external projects are therefore mirrored into DroidCompiler-private storage before native compilation.

```text
External folder (SAF)
        |
        | import / incremental refresh
        v
DroidCompiler project workspace
        |
        +-- mirrored source tree
        +-- .droidx/build/        compiler objects/state/libprogram.so
        +-- .droidx/export/       APK export metadata/icon
        |
        +--> embedded Clang / LLD
```

`ProjectManager` owns the active/recent project registry and persisted SAF permissions. `ProjectStore` resolves files through the active workspace and synchronizes user edits back to linked folders when needed.

A project-owned `build/` directory is ordinary source content. DroidCompiler owns only `.droidx/`.

## Build pipeline

`CompilerEngine` resolves project/build settings, source files and libraries, then invokes the embedded compiler/linker. Makefile compatibility is implemented internally by `MakefileCompat`; DroidCompiler does not require a shell or GNU make for supported C4droid-style projects.

Optional native packages are managed separately from the core toolchain and are made available to the compiler/runtime when activated.

## Run pipeline

Console applications run through the console runner/service. SDL2/OpenGL applications use the SDL runner process and native `runnerbridge` preflight/load path. Foreground services are available for user-initiated programs that need to remain active while the IDE is backgrounded.

## APK export pipeline

```text
Android Studio build
   -> :app
   -> buildSrc tasks prepare embedded toolchain/SDL/headers
   -> export-runtime compiled into an internal template APK asset
   -> DroidCompiler.apk

Runtime export
   -> active project build
   -> .droidx/build/libprogram*.so
   -> export metadata/icon
   -> patch internal template
   -> add native program/runtime/assets
   -> sign and verify
   -> Install / Save / Share
```

`export-runtime/` is source input to the build-time template generator, not a separately installed application.

## UI layer

`MainActivity` coordinates the compact IDE shell, project/file tree explorer, build/run/export actions, settings and logs. `CodeEditorView` owns editor presentation such as the gutter, active-line rendering and lightweight syntax highlighting. Build data remains persisted by `ProjectStore` and consumed by `CompilerEngine`; UI layout changes do not own compiler state.
