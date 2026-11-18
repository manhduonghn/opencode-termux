/*
 * Minimal ELF64 program-interpreter (PT_INTERP) patcher.
 *
 * Android ships the bionic linker, while the official OpenCode builds are linked
 * against glibc and carry a PT_INTERP of a desktop Linux path such as
 * "/lib64/ld-linux-x86-64.so.2". To run such a binary natively the PT_INTERP has
 * to be pointed at the glibc loader of Termux instead.
 *
 * This is a small, dependency free replacement for "patchelf --set-interpreter".
 * It only rewrites the interpreter string of the PT_INTERP program header:
 *
 *   - when the new path fits into the slot of the old one it is overwritten in
 *     place (a few bytes, no matter how big the binary is);
 *   - otherwise the path is appended to the end of the file and the header is
 *     repointed at it. The kernel reads the interpreter straight from the file,
 *     so it does not have to live inside a loadable segment.
 *
 * Only ELF64 little-endian files are handled, which covers every ABI the app is
 * built for (arm64-v8a and x86_64).
 */

#ifndef OPENCODE_ELFPATCH_H
#define OPENCODE_ELFPATCH_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Result codes, the value of the first one is 0 so "== 0" means success. */
#define ELFPATCH_OK          0
#define ELFPATCH_EINVAL     -1 /* a bad argument, eg. a null path */
#define ELFPATCH_EOPEN      -2 /* the file could not be opened for reading/writing */
#define ELFPATCH_EIO        -3 /* a read or write failed */
#define ELFPATCH_EFORMAT    -4 /* not an ELF64 little-endian executable */
#define ELFPATCH_ENOINTERP  -5 /* the file has no PT_INTERP program header */
#define ELFPATCH_ELONG      -6 /* the new interpreter path is too long */

/**
 * Read the interpreter path of an ELF64 file.
 *
 * @param path     file to read
 * @param out      buffer that receives a NUL terminated path
 * @param out_size size of {@code out}, at least 2 bytes
 * @return {@link ELFPATCH_OK} or one of the error codes
 */
int elf_get_interpreter(const char *path, char *out, size_t out_size);

/**
 * Replace the interpreter path (PT_INTERP) of an ELF64 file.
 *
 * The file is modified in place: the header is rewritten and, when the new path
 * does not fit into the old slot, it is appended to the end of the file. The
 * rest of the file is left untouched.
 *
 * @return {@link ELFPATCH_OK} or one of the error codes
 */
int elf_set_interpreter(const char *path, const char *interpreter);

/** @return a human readable description of an {@link ELFPATCH_OK} style code. */
const char *elfpatch_strerror(int code);

#ifdef __cplusplus
}
#endif

#endif /* OPENCODE_ELFPATCH_H */
