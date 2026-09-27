#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

typedef int (*main_fn)(int, char**);

static JavaVM* g_vm = NULL;
static jobject g_context = NULL;
static jobject g_activity = NULL;
static jclass g_host_api = NULL;

static int g_pty_slave = -1;
static int g_last_exit_code = -1;
static char g_last_error[1024] = {0};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static JNIEnv* current_env(void) {
    if (!g_vm) return NULL;
    JNIEnv* env = NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void**) &env, JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) return env;
    }
    return NULL;
}

static void replace_global(JNIEnv* env, jobject* slot, jobject value) {
    if (*slot) {
        (*env)->DeleteGlobalRef(env, *slot);
        *slot = NULL;
    }
    if (value) *slot = (*env)->NewGlobalRef(env, value);
}

static void cache_host_api(JNIEnv* env) {
    if (g_host_api) return;
    jclass local = (*env)->FindClass(env, "com/droidx/AndroidHostApi");
    if (local) {
        g_host_api = (jclass) (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
    }
}

__attribute__((visibility("default"))) void* droidx_android_get_jni_env(void) {
    return (void*) current_env();
}

__attribute__((visibility("default"))) void* droidx_android_get_activity(void) {
    return (void*) g_activity;
}

__attribute__((visibility("default"))) int droidx_android_sdk_int(void) {
    JNIEnv* env = current_env();
    if (!env) return -1;
    cache_host_api(env);
    if (!g_host_api) return -2;
    jmethodID m = (*env)->GetStaticMethodID(env, g_host_api, "sdkInt", "()I");
    if (!m) return -3;
    return (int) (*env)->CallStaticIntMethod(env, g_host_api, m);
}

__attribute__((visibility("default"))) int droidx_android_notify(const char* title, const char* text) {
    JNIEnv* env = current_env();
    if (!env || !g_context) return -1;
    cache_host_api(env);
    if (!g_host_api) return -2;
    jmethodID m = (*env)->GetStaticMethodID(env, g_host_api, "notifyNative", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)I");
    if (!m) return -3;
    jstring jt = (*env)->NewStringUTF(env, title ? title : "DroidCompiler program");
    jstring jx = (*env)->NewStringUTF(env, text ? text : "");
    jint rc = (*env)->CallStaticIntMethod(env, g_host_api, m, g_context, jt, jx);
    (*env)->DeleteLocalRef(env, jt);
    (*env)->DeleteLocalRef(env, jx);
    return (int) rc;
}

__attribute__((visibility("default"))) int droidx_android_keep_cpu_awake(int enabled) {
    JNIEnv* env = current_env();
    if (!env || !g_context) return -1;
    cache_host_api(env);
    if (!g_host_api) return -2;
    jmethodID m = (*env)->GetStaticMethodID(env, g_host_api, "setCpuWakeLock", "(Landroid/content/Context;Z)I");
    if (!m) return -3;
    return (int) (*env)->CallStaticIntMethod(env, g_host_api, m, g_context, enabled ? JNI_TRUE : JNI_FALSE);
}

__attribute__((visibility("default"))) int droidx_android_open_url(const char* url) {
    JNIEnv* env = current_env();
    if (!env || !g_context || !url) return -1;
    cache_host_api(env);
    if (!g_host_api) return -2;
    jmethodID m = (*env)->GetStaticMethodID(env, g_host_api, "openUrl", "(Landroid/content/Context;Ljava/lang/String;)I");
    if (!m) return -3;
    jstring ju = (*env)->NewStringUTF(env, url);
    jint rc = (*env)->CallStaticIntMethod(env, g_host_api, m, g_context, ju);
    (*env)->DeleteLocalRef(env, ju);
    return (int) rc;
}

__attribute__((visibility("default"))) void droidx_android_log(int priority, const char* tag, const char* text) {
    if (priority < ANDROID_LOG_VERBOSE || priority > ANDROID_LOG_FATAL) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, tag ? tag : "DroidXUser", text ? text : "");
}

static void set_last_error(const char* text) {
    if (!text) text = "";
    snprintf(g_last_error, sizeof(g_last_error), "%s", text);
}

static void append_line(char* dst, size_t cap, const char* prefix, const char* value) {
    if (!dst || cap == 0) return;
    size_t used = strlen(dst);
    if (used >= cap - 1) return;
    snprintf(dst + used, cap - used, "%s%s\n", prefix ? prefix : "", value ? value : "");
}

/* v1.6.3: preload optional runtimes staged next to the user program.
 * Android linker namespaces do not honor PREFIX/lib like a normal desktop
 * LD_LIBRARY_PATH. Loading the exact sibling by absolute path and RTLD_GLOBAL
 * makes its SONAME available before libprogram.so resolves DT_NEEDED. */
static void preload_sibling_runtime(const char* program, const char* soname, char* status, size_t statusCap) {
    if (!program || !soname) return;
    const char* slash = strrchr(program, '/');
    if (!slash) return;
    size_t dirLen = (size_t)(slash - program);
    char path[2048];
    if (dirLen + 1 + strlen(soname) + 1 > sizeof(path)) return;
    memcpy(path, program, dirLen);
    path[dirLen] = '/';
    strcpy(path + dirLen + 1, soname);
    if (access(path, R_OK) != 0) return;
    dlerror();
    void* dep = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!dep) {
        append_line(status, statusCap, "runtime preload failed: ", dlerror());
    }
}

static void preload_program_runtimes(const char* lib, char* status, size_t statusCap) {
    preload_sibling_runtime(lib, "libsqlite3.so", status, statusCap);
}

static int run_loaded_main(const char* lib, char* status, size_t statusCap) {
    preload_program_runtimes(lib, status, statusCap);
    void* handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        append_line(status, statusCap, "dlopen failed: ", dlerror());
        return -1;
    }

    dlerror();
    main_fn fn = (main_fn) dlsym(handle, "main");
    const char* symErr = dlerror();
    if (symErr != NULL || fn == NULL) {
        append_line(status, statusCap, "dlsym(main) failed: ", symErr ? symErr : "symbol missing");
        dlclose(handle);
        return -1;
    }

    char arg0[] = "droidx-program";
    char* argv[] = { arg0, NULL };
    int exitCode = fn(1, argv);
    fflush(NULL);
    dlclose(handle);
    fflush(NULL);
    return exitCode;
}

JNIEXPORT void JNICALL
Java_com_droidx_RunnerBridge_nativeConfigureRuntime(JNIEnv* env, jclass clazz,
                                                     jobject jcontext,
                                                     jstring jprefix, jstring jhome,
                                                     jstring jtmp, jstring jca,
                                                     jstring jproject, jstring jassets) {
    (void) clazz;
    replace_global(env, &g_context, jcontext);
    cache_host_api(env);
    const char* prefix = jprefix ? (*env)->GetStringUTFChars(env, jprefix, NULL) : NULL;
    const char* home = jhome ? (*env)->GetStringUTFChars(env, jhome, NULL) : NULL;
    const char* tmp = jtmp ? (*env)->GetStringUTFChars(env, jtmp, NULL) : NULL;
    const char* ca = jca ? (*env)->GetStringUTFChars(env, jca, NULL) : NULL;
    const char* project = jproject ? (*env)->GetStringUTFChars(env, jproject, NULL) : NULL;
    const char* assets = jassets ? (*env)->GetStringUTFChars(env, jassets, NULL) : NULL;

    if (prefix && *prefix) setenv("PREFIX", prefix, 1);
    if (home && *home) setenv("HOME", home, 1);
    if (tmp && *tmp) setenv("TMPDIR", tmp, 1);
    if (ca && *ca) {
        setenv("CURL_CA_BUNDLE", ca, 1);
        setenv("SSL_CERT_FILE", ca, 1);
    }
    if (project && *project) {
        setenv("DROIDX_PROJECT_DIR", project, 1);
        chdir(project);
    }
    if (assets && *assets) setenv("DROIDX_ASSET_DIR", assets, 1);
    setenv("TERM", "xterm-256color", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("LC_ALL", "C.UTF-8", 1);

    if (jprefix && prefix) (*env)->ReleaseStringUTFChars(env, jprefix, prefix);
    if (jhome && home) (*env)->ReleaseStringUTFChars(env, jhome, home);
    if (jtmp && tmp) (*env)->ReleaseStringUTFChars(env, jtmp, tmp);
    if (jca && ca) (*env)->ReleaseStringUTFChars(env, jca, ca);
    if (jproject && project) (*env)->ReleaseStringUTFChars(env, jproject, project);
    if (jassets && assets) (*env)->ReleaseStringUTFChars(env, jassets, assets);
}

JNIEXPORT void JNICALL
Java_com_droidx_RunnerBridge_nativeSetHostActivity(JNIEnv* env, jclass clazz, jobject activity) {
    (void) clazz;
    replace_global(env, &g_activity, activity);
}

JNIEXPORT jint JNICALL
Java_com_droidx_RunnerBridge_nativeCreatePty(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (g_pty_slave >= 0) {
        close(g_pty_slave);
        g_pty_slave = -1;
    }
    g_last_exit_code = -1;
    set_last_error("");

    int master = -1;
    int slave = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = 40;
    ws.ws_col = 120;

    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        char buf[512];
        snprintf(buf, sizeof(buf), "%s", strerror(errno));
        set_last_error(buf);
        return -1;
    }

    // Keep slave owned by native code until nativeRunPty() takes it.
    g_pty_slave = slave;
    return master;
}

JNIEXPORT jstring JNICALL
Java_com_droidx_RunnerBridge_nativeRunPty(JNIEnv* env, jclass clazz, jstring jlib) {
    (void) clazz;
    const char* lib = (*env)->GetStringUTFChars(env, jlib, NULL);
    char status[4096];
    status[0] = 0;
    g_last_exit_code = -1;

    int slave = g_pty_slave;
    g_pty_slave = -1;
    if (slave < 0) {
        set_last_error("PTY slave is not available");
        (*env)->ReleaseStringUTFChars(env, jlib, lib);
        return (*env)->NewStringUTF(env, "PTY slave is not available");
    }

    int savedIn = dup(STDIN_FILENO);
    int savedOut = dup(STDOUT_FILENO);
    int savedErr = dup(STDERR_FILENO);

    if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) {
        snprintf(status, sizeof(status), "dup2(PTY) failed: %s", strerror(errno));
        set_last_error(status);
        goto restore;
    }
    if (slave > STDERR_FILENO) close(slave);
    slave = -1;

    // Make prompts and printf/cout output visible promptly in the terminal.
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    g_last_exit_code = run_loaded_main(lib, status, sizeof(status));
    if (status[0] == 0) {
        snprintf(status, sizeof(status), "Program returned normally");
    }

restore:
    fflush(NULL);
    if (savedIn >= 0) dup2(savedIn, STDIN_FILENO);
    if (savedOut >= 0) dup2(savedOut, STDOUT_FILENO);
    if (savedErr >= 0) dup2(savedErr, STDERR_FILENO);
    if (savedIn >= 0) close(savedIn);
    if (savedOut >= 0) close(savedOut);
    if (savedErr >= 0) close(savedErr);
    if (slave >= 0) close(slave);

    (*env)->ReleaseStringUTFChars(env, jlib, lib);
    return (*env)->NewStringUTF(env, status);
}

JNIEXPORT jstring JNICALL
Java_com_droidx_RunnerBridge_nativeLastError(JNIEnv* env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, g_last_error);
}

JNIEXPORT jint JNICALL
Java_com_droidx_RunnerBridge_nativeLastExitCode(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_last_exit_code;
}

JNIEXPORT jstring JNICALL
Java_com_droidx_RunnerBridge_nativeRun(JNIEnv* env, jclass clazz, jstring jlib, jstring jout) {
    (void) clazz;
    const char* lib = (*env)->GetStringUTFChars(env, jlib, NULL);
    const char* outPath = (*env)->GetStringUTFChars(env, jout, NULL);

    char status[4096];
    status[0] = 0;
    int savedOut = -1, savedErr = -1, fd = -1;
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
        goto restore;
    }

    exitCode = run_loaded_main(lib, status, sizeof(status));

restore:
    if (savedOut >= 0) dup2(savedOut, STDOUT_FILENO);
    if (savedErr >= 0) dup2(savedErr, STDERR_FILENO);

done:
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

/* v1.6.2: preflight the exact user .so in the :sdlrunner linker namespace. */
JNIEXPORT jstring JNICALL
Java_com_droidx_RunnerBridge_nativeProbeSharedObject(JNIEnv* env, jclass clazz, jstring jlib, jstring jsymbol) {
    (void) clazz;
    if (!jlib || !jsymbol) return (*env)->NewStringUTF(env, "ERROR invalid probe arguments");
    const char* lib = (*env)->GetStringUTFChars(env, jlib, NULL);
    const char* symbol = (*env)->GetStringUTFChars(env, jsymbol, NULL);
    char result[4096];
    result[0] = 0;

    preload_program_runtimes(lib, result, sizeof(result));
    dlerror();
    void* handle = dlopen(lib, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char* err = dlerror();
        snprintf(result, sizeof(result), "DLOPEN_FAIL path=%s error=%s", lib, err ? err : "unknown");
    } else {
        dlerror();
        void* fn = dlsym(handle, symbol);
        const char* err = dlerror();
        if (err || !fn) {
            snprintf(result, sizeof(result), "DLSYM_FAIL symbol=%s error=%s", symbol, err ? err : "not found");
        } else {
            snprintf(result, sizeof(result), "OK dlopen=success symbol=%s addr=%p", symbol, fn);
        }
        /* Intentionally keep the handle referenced. SDL nativeRunMain will dlopen the
           same pathname and reuse it; this also prevents an unload/reload gap. */
    }

    (*env)->ReleaseStringUTFChars(env, jlib, lib);
    (*env)->ReleaseStringUTFChars(env, jsymbol, symbol);
    return (*env)->NewStringUTF(env, result);
}
