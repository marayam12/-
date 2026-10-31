/*
 * Native crash guard for RenexaMC.
 *
 * Installs OS-level signal handlers (SIGSEGV, SIGABRT, SIGBUS, SIGFPE,
 * SIGILL) UNDER the JVM's own crash handler. If the process is about to
 * die from one of these, we write a small marker file first (using only
 * async-signal-safe syscalls: open/write/fsync/close - no malloc, no
 * printf), then re-raise into whatever handler was previously installed
 * (normally Android's own debuggerd) so crash reporting still works
 * normally.
 *
 * On the NEXT app launch, CrashGuard.hadPreviousCrash() checks for that
 * marker file. If it's there, the app knows last run ended in a hard
 * crash (not a normal close) and can react - e.g. clear a possibly
 * corrupted WebView cache instead of loading it again and crashing the
 * same way forever.
 */

#include <jni.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>

/*
 * Size padding: makes librenexamc.so about 1.41 MB on disk.
 * Change RENEXAMC_PAD_BYTES to make the library bigger or smaller
 * (the rest of the library is only ~16 KB; 1.41 MB = 1,478,492 bytes).
 * Zeros are stored in the file (.rodata), but a ZIP/APK compresses them to
 * almost nothing, so the installed APK does not grow by this amount.
 */
#define RENEXAMC_PAD_BYTES 1462000
const unsigned char g_renexamc_pad[RENEXAMC_PAD_BYTES] = { 1 };

static char g_flag_path[512];

static struct sigaction g_old_segv;
static struct sigaction g_old_abrt;
static struct sigaction g_old_bus;
static struct sigaction g_old_fpe;
static struct sigaction g_old_ill;

static void write_crash_flag(void) {
    int fd = open(g_flag_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        static const char msg[] = "crash";
        write(fd, msg, sizeof(msg) - 1);
        fsync(fd);
        close(fd);
    }
}

static struct sigaction *old_action_for(int sig) {
    switch (sig) {
        case SIGSEGV: return &g_old_segv;
        case SIGABRT: return &g_old_abrt;
        case SIGBUS:  return &g_old_bus;
        case SIGFPE:  return &g_old_fpe;
        case SIGILL:  return &g_old_ill;
        default:      return NULL;
    }
}

static void crash_handler(int sig, siginfo_t *info, void *ucontext) {
    write_crash_flag();

    struct sigaction *old = old_action_for(sig);
    if (old != NULL && (old->sa_flags & SA_SIGINFO) && old->sa_sigaction != NULL) {
        old->sa_sigaction(sig, info, ucontext);
        return;
    }
    if (old != NULL && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
        return;
    }

    /* No usable previous handler: restore default behavior and re-raise
       so the OS still produces a normal crash/tombstone. */
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_one(int sig, struct sigaction *old_out) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, old_out);
}

JNIEXPORT void JNICALL
Java_com_renexamc_lib_CrashGuard_nativeInstall(JNIEnv *env, jobject thiz, jstring flagPath) {
    const char *path = (*env)->GetStringUTFChars(env, flagPath, NULL);
    if (path != NULL) {
        strncpy(g_flag_path, path, sizeof(g_flag_path) - 1);
        g_flag_path[sizeof(g_flag_path) - 1] = '\0';
        (*env)->ReleaseStringUTFChars(env, flagPath, path);
    }

    /* Reference the padding so the linker keeps it. */
    volatile unsigned char pad_touch = g_renexamc_pad[0];
    (void)pad_touch;

    install_one(SIGSEGV, &g_old_segv);
    install_one(SIGABRT, &g_old_abrt);
    install_one(SIGBUS,  &g_old_bus);
    install_one(SIGFPE,  &g_old_fpe);
    install_one(SIGILL,  &g_old_ill);
}

JNIEXPORT jboolean JNICALL
Java_com_renexamc_lib_CrashGuard_nativeHadCrash(JNIEnv *env, jobject thiz, jstring flagPath) {
    const char *path = (*env)->GetStringUTFChars(env, flagPath, NULL);
    jboolean result = JNI_FALSE;
    if (path != NULL) {
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            result = JNI_TRUE;
            close(fd);
        }
        (*env)->ReleaseStringUTFChars(env, flagPath, path);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_renexamc_lib_CrashGuard_nativeClearCrash(JNIEnv *env, jobject thiz, jstring flagPath) {
    const char *path = (*env)->GetStringUTFChars(env, flagPath, NULL);
    if (path != NULL) {
        unlink(path);
        (*env)->ReleaseStringUTFChars(env, flagPath, path);
    }
}
