# DroidCompiler v1.5.1 — Integrated APK Export

## One installed APK

The root Gradle project contains only `:app`. There is no second Android application module for the packager. The export runtime source lives under `export-runtime/` and `ExportTemplateEmbedder` builds it into `app/build/generated/droidxAssets/droidx-export-template.apk` during DroidCompiler's build. That file is then packaged as an internal asset of **DroidCompiler.apk**.

The internal template is never installed as a separate application.

## User flow

1. Open/build/run a C/C++ project.
2. Tap **APK**.
3. Choose app name, package ID, version name, version code, icon, orientation and runtime options.
4. DroidCompiler rebuilds the native project if needed.
5. The integrated exporter patches the binary manifest and icon.
6. It inserts `libprogram.so`, SDL2/runnerbridge when required, `libc++_shared.so` when required, and project assets.
7. Native `.so` entries are STORED and 16 KiB aligned.
8. The APK is signed with the bundled development key using v1/v2/v3 signing and verified with `apksig`.
9. Android PackageManager verifies package ID, versionName and versionCode.
10. Choose **Install**, **Save** or **Share**.

## Per-project export metadata

Stored in `project.properties`:

```text
apk.name=ChainScan
apk.package=com.example.chainscan
apk.versionName=1.0.0
apk.versionCode=1
apk.orientation=portrait
apk.foreground=true
apk.legacyStorage=true
```

The custom icon is stored in the DroidCompiler workspace at `.droidx/export/icon.png`.

## Current limitations

- The bundled key is a development key; release keystore import is still a later step.
- Extra arbitrary manifest permissions are not injected dynamically yet.
- Custom project Java/Kotlin sources are not yet compiled into the exported APK.
- Optional dynamically linked runtimes such as curl/OpenSSL/zstd/SQLite/libpng/libjpeg currently block export until runtime dependency bundling is added.
- Export currently targets the ABI of the device doing the build/export. Multi-ABI/universal APK export is a later step.

## v1.5.4 build-state fix

APK metadata is now stored in `.droidx/export/export.properties` instead of the project's `project.properties`. Changing app name, package id, version, orientation, foreground export mode, legacy-storage export mode, or icon no longer invalidates the compiled C++ artifact.

A successful C++ link writes `.droidx/build/build-state.properties`. RUN and APK Export validate that state against the active `libprogram.so`, the effective build-system fingerprint, the Makefile when applicable, and the actual Makefile source set. Export metadata is deliberately excluded.
