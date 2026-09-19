# v0.6 architecture

## Runtime

```
Editor/main.cpp
  -> Clang C++20 --target=<abi>-linux-android35
  -> main.o
  -> LLD -flavor gnu
  -> read-only libprogram_<id>.so
      -> console: :runner -> dlopen -> main
      -> graphics: :sdlrunner -> SDLActivity -> SDL_main -> GLES3/touch
```

## Networking

- TCP/UDP/DNS: Android Bionic/POSIX sockets.
- HTTP/HTTPS: embedded libcurl.
- WS/WSS: libcurl WebSocket API using CONNECT_ONLY=2, curl_ws_send and curl_ws_recv.
- Runtime bridge sets CURL_CA_BUNDLE and SSL_CERT_FILE in both runner processes.

## Orientation

`projects/HelloCpp/project.properties` currently defaults to `orientation=landscape`. The value is already consumed by SdlRunnerActivity; UI selection and APK manifest export will be connected later.
