#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*main_fn)(int, char**);

static void append_line(char* dst, size_t cap, const char* prefix, const char* value) {
    if (!dst || cap == 0) return;
    size_t used = strlen(dst);
    if (used >= cap - 1) return;
    snprintf(dst + used, cap - used, "%s%s\n", prefix ? prefix : "", value ? value : "");
}


JNIEXPORT void JNICALL
Java_com_droidx_RunnerBridge_nativeConfigureRuntime(JNIEnv* env, jclass clazz,
                                                     jstring jprefix, jstring jhome,
                                                     jstring jtmp, jstring jca) {
    (void) clazz;
    const char* prefix = jprefix ? (*env)->GetStringUTFChars(env, jprefix, NULL) : NULL;
    const char* home = jhome ? (*env)->GetStringUTFChars(env, jhome, NULL) : NULL;
    const char* tmp = jtmp ? (*env)->GetStringUTFChars(env, jtmp, NULL) : NULL;
    const char* ca = jca ? (*env)->GetStringUTFChars(env, jca, NULL) : NULL;

    if (prefix && *prefix) setenv("PREFIX", prefix, 1);
    if (home && *home) setenv("HOME", home, 1);
    if (tmp && *tmp) setenv("TMPDIR", tmp, 1);
    if (ca && *ca) {
        setenv("CURL_CA_BUNDLE", ca, 1);
        setenv("SSL_CERT_FILE", ca, 1);
    }
    setenv("LANG", "C", 1);
    setenv("LC_ALL", "C", 1);

    if (jprefix && prefix) (*env)->ReleaseStringUTFChars(env, jprefix, prefix);
    if (jhome && home) (*env)->ReleaseStringUTFChars(env, jhome, home);
    if (jtmp && tmp) (*env)->ReleaseStringUTFChars(env, jtmp, tmp);
    if (jca && ca) (*env)->ReleaseStringUTFChars(env, jca, ca);
}

JNIEXPORT jstring JNICALL
Java_com_droidx_RunnerBridge_nativeRun(JNIEnv* env, jclass clazz, jstring jlib, jstring jout) {
    (void) clazz;
    const char* lib = (*env)->GetStringUTFChars(env, jlib, NULL);
    const char* outPath = (*env)->GetStringUTFChars(env, jout, NULL);

    char status[4096];
    status[0] = 0;
    int savedOut = -1, savedErr = -1, fd = -1;
    void* handle = NULL;
    int exitCode = -1;

    savedOut = dup(STDOUT_FILENO);
    savedErr = dup(STDERR_FILENO);
    fd = open(outPath, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (fd < 0) {
        snprintf(status, sizeof(status), "Could not open runner output: %s", strerror(errno));
        goto done;
    }

    if (dup2(fd, STDOUT_FILENO) < 0 || dup2(fd, STDERR_FILENO) < 0) {
        snprintf(status, sizeof(status), "dup2 failed: %s", strerror(errno));
        goto done;
    }

    handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        append_line(status, sizeof(status), "dlopen failed: ", dlerror());
        goto restore;
    }

    dlerror();
    main_fn fn = (main_fn) dlsym(handle, "main");
    const char* symErr = dlerror();
    if (symErr != NULL || fn == NULL) {
        append_line(status, sizeof(status), "dlsym(main) failed: ", symErr ? symErr : "symbol missing");
        goto restore;
    }

    {
        char arg0[] = "droidx-program";
        char* argv[] = { arg0, NULL };
        exitCode = fn(1, argv);
    }

    // stdout is normally synchronized with the C++ iostreams by default.
    // Keep redirection active through dlclose so global destructors are captured too.
    fflush(NULL);
    dlclose(handle);
    handle = NULL;
    fflush(NULL);

restore:
    if (savedOut >= 0) dup2(savedOut, STDOUT_FILENO);
    if (savedErr >= 0) dup2(savedErr, STDERR_FILENO);

done:
    if (handle) dlclose(handle);
    if (fd >= 0) close(fd);
    if (savedOut >= 0) close(savedOut);
    if (savedErr >= 0) close(savedErr);

    if (status[0] == 0) {
        snprintf(status, sizeof(status), "EXIT_CODE=%d", exitCode);
    }

    (*env)->ReleaseStringUTFChars(env, jlib, lib);
    (*env)->ReleaseStringUTFChars(env, jout, outPath);
    return (*env)->NewStringUTF(env, status);
}
