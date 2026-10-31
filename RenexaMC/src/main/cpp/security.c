/*
 * libsecurity.so - native half of the app's anti-tamper check (RenexaMC.lib.SecurityGuard).
 *
 * This does two things:
 *
 * 1. Java_com_renexamc_lib_SecurityGuard_nativeSelfCheck(): a best-effort, native-side
 *    check for an attached debugger or a known app-patching/hooking framework (Frida,
 *    Xposed/LSPosed, Substrate, "whale"). This is NOT antivirus and cannot scan for
 *    generic malware - no small app-side library can do that honestly. What it adds is
 *    a second place (compiled native code, not just Kotlin) that has to be patched for
 *    someone to tamper with the app and still pass the check.
 *
 *    The actual, reliable "was this app's code/name/icon changed" check is the signing
 *    certificate comparison done on the Kotlin side (SecurityGuard.kt): Android requires
 *    ANY modified APK to be re-signed, and a re-signed APK has a different certificate
 *    unless it was signed with the original renexamc.keystore. That is what really
 *    detects a cloned/retitled/relogo'd copy; this native file is a second, harder-to-
 *    patch layer on top of it.
 *
 * 2. Size padding: makes libsecurity.so visibly grow with every update, the same way
 *    librenexamc.so (crashguard.c) does. Bump SECURITY_PAD_MB by 1 with every update you
 *    ship (see the comment at its definition) so a stale/untouched libsecurity.so (for
 *    example someone copying an old one back in to dodge a new check) stands out by size.
 */

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

/* Bump this by 1 (so +1 MB) each time you ship an update. Started at 2 for version 1.5.6. */
#define SECURITY_PAD_MB 2
#define SECURITY_PAD_BYTES (SECURITY_PAD_MB * 1024 * 1024)
const unsigned char g_security_pad[SECURITY_PAD_BYTES] = { 1 };

static int file_contains(const char *path, const char *needle) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[512];
    int found = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, needle) != NULL) { found = 1; break; }
    }
    fclose(f);
    return found;
}

/* TracerPid != 0 in /proc/self/status means a debugger/tracer is attached right now. */
static int debugger_attached(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    int attached = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = 0;
            sscanf(line + 10, "%d", &pid);
            attached = (pid != 0);
            break;
        }
    }
    fclose(f);
    return attached;
}

/* A hooking/patching framework usually shows up as a loaded library in /proc/self/maps. */
static int hook_framework_present(void) {
    static const char *needles[] = {
        "frida", "re.frida.server", "xposed", "lspatch", "lsposed",
        "substrate", "whale_", "edxp", NULL
    };
    for (int i = 0; needles[i] != NULL; i++) {
        if (file_contains("/proc/self/maps", needles[i])) return 1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_renexamc_lib_SecurityGuard_nativeSelfCheck(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;

    /* Reference the padding so the linker keeps it in the binary. */
    volatile unsigned char pad_touch = g_security_pad[0];
    (void) pad_touch;

    if (debugger_attached()) return JNI_FALSE;
    if (hook_framework_present()) return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_renexamc_lib_SecurityGuard_nativePadSize(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) sizeof(g_security_pad);
}
