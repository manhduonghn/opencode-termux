/*
 * "oc-patch" command line tool.
 *
 * This is the shell-facing twin of the JNI patcher. It is packaged inside the APK
 * under a "lib*.so" name (so that the Android build packs it) and copied to
 * $PREFIX/bin/oc-patch by the app. The opencode launcher uses it to repair the
 * binary when OpenCode replaced it with an unpatched build, without depending on
 * the external patchelf tool anymore.
 *
 * Usage:
 *   oc-patch set-interpreter <interpreter> <file>
 *   oc-patch get-interpreter <file>
 *   oc-patch --version
 */

#include <stdio.h>
#include <string.h>

#include "elfpatch.h"

#define OCPATCH_VERSION "oc-patch 1"

static int usage(void) {
    fprintf(stderr,
        "usage: oc-patch set-interpreter <interpreter> <file>\n"
        "       oc-patch get-interpreter <file>\n"
        "       oc-patch --version\n");
    return 2;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts(OCPATCH_VERSION);
        return 0;
    }

    if (argc == 4 && strcmp(argv[1], "set-interpreter") == 0) {
        int rc = elf_set_interpreter(argv[3], argv[2]);
        if (rc != ELFPATCH_OK) {
            fprintf(stderr, "oc-patch: %s: %s\n", argv[3], elfpatch_strerror(rc));
            return 1;
        }
        return 0;
    }

    if (argc == 3 && (strcmp(argv[1], "get-interpreter") == 0 || strcmp(argv[1], "print-interpreter") == 0)) {
        char buffer[4096];
        int rc = elf_get_interpreter(argv[2], buffer, sizeof(buffer));
        if (rc != ELFPATCH_OK) {
            fprintf(stderr, "oc-patch: %s: %s\n", argv[2], elfpatch_strerror(rc));
            return 1;
        }
        puts(buffer);
        return 0;
    }

    return usage();
}
