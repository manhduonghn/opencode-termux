#include "elfpatch.h"

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/* A PT_INTERP is a path, it can never be longer than this. */
#define ELFPATCH_MAX_INTERP ((size_t) 4096)

/** Read exactly n bytes at offset, returns 0 on success. */
static int read_full(int fd, void *buf, size_t n, off_t offset) {
    size_t done = 0;
    while (done < n) {
        ssize_t r = pread(fd, (char *) buf + done, n - done, offset + (off_t) done);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return -1; /* unexpected end of file */
        done += (size_t) r;
    }
    return 0;
}

/** Write exactly n bytes at offset, returns 0 on success. */
static int write_full(int fd, const void *buf, size_t n, off_t offset) {
    size_t done = 0;
    while (done < n) {
        ssize_t w = pwrite(fd, (const char *) buf + done, n - done, offset + (off_t) done);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        done += (size_t) w;
    }
    return 0;
}

/** @return 0 when the header describes an ELF64 little-endian executable. */
static int check_header(const Elf64_Ehdr *eh) {
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return -1;
    if (eh->e_ident[EI_CLASS] != ELFCLASS64) return -1;
    if (eh->e_ident[EI_DATA] != ELFDATA2LSB) return -1;
    if (eh->e_ident[EI_VERSION] != EV_CURRENT) return -1;
    if (eh->e_phentsize < sizeof(Elf64_Phdr)) return -1;
    if (eh->e_phnum == 0) return -1;
    return 0;
}

/**
 * Find the PT_INTERP program header.
 *
 * @param out_phdr receives a copy of the header
 * @param out_offset receives the file offset of the header itself
 * @return 0 when it was found, -1 on a read error, 1 when the file has none
 */
static int find_interp(int fd, const Elf64_Ehdr *eh, Elf64_Phdr *out_phdr, off_t *out_offset) {
    for (int i = 0; i < (int) eh->e_phnum; i++) {
        off_t offset = (off_t) eh->e_phoff + (off_t) i * (off_t) eh->e_phentsize;
        Elf64_Phdr ph;
        if (read_full(fd, &ph, sizeof(ph), offset) != 0) return -1;
        if (ph.p_type != PT_INTERP) continue;
        *out_phdr = ph;
        *out_offset = offset;
        return 0;
    }
    return 1;
}

int elf_get_interpreter(const char *path, char *out, size_t out_size) {
    if (path == NULL || out == NULL || out_size < 2) return ELFPATCH_EINVAL;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return ELFPATCH_EOPEN;

    int rc;
    Elf64_Ehdr eh;
    if (read_full(fd, &eh, sizeof(eh), 0) != 0) {
        rc = ELFPATCH_EIO;
        goto done;
    }
    if (check_header(&eh) != 0) {
        rc = ELFPATCH_EFORMAT;
        goto done;
    }

    Elf64_Phdr ph = {0};
    off_t ph_offset = 0;
    int found = find_interp(fd, &eh, &ph, &ph_offset);
    if (found < 0) {
        rc = ELFPATCH_EIO;
        goto done;
    }
    if (found > 0) {
        rc = ELFPATCH_ENOINTERP;
        goto done;
    }

    if (ph.p_filesz == 0 || ph.p_filesz > ELFPATCH_MAX_INTERP) {
        rc = ELFPATCH_EFORMAT;
        goto done;
    }

    size_t n = ph.p_filesz < out_size - 1 ? (size_t) ph.p_filesz : out_size - 1;
    if (read_full(fd, out, n, (off_t) ph.p_offset) != 0) {
        rc = ELFPATCH_EIO;
        goto done;
    }
    out[n] = '\0';
    rc = ELFPATCH_OK;

done:
    close(fd);
    return rc;
}

int elf_set_interpreter(const char *path, const char *interpreter) {
    if (path == NULL || interpreter == NULL) return ELFPATCH_EINVAL;

    size_t length = strlen(interpreter) + 1; /* including the terminating NUL */
    if (length < 2 || length > ELFPATCH_MAX_INTERP) return ELFPATCH_ELONG;

    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return ELFPATCH_EOPEN;

    int rc;
    Elf64_Ehdr eh;
    if (read_full(fd, &eh, sizeof(eh), 0) != 0) {
        rc = ELFPATCH_EIO;
        goto done;
    }
    if (check_header(&eh) != 0) {
        rc = ELFPATCH_EFORMAT;
        goto done;
    }

    Elf64_Phdr ph = {0};
    off_t ph_offset = 0;
    int found = find_interp(fd, &eh, &ph, &ph_offset);
    if (found < 0) {
        rc = ELFPATCH_EIO;
        goto done;
    }
    if (found > 0) {
        rc = ELFPATCH_ENOINTERP;
        goto done;
    }

    if (length <= ph.p_filesz) {
        /*
         * The new path fits into the slot of the old one. Write it and make sure
         * that the last byte of the slot is NUL, which is what the kernel checks.
         */
        if (write_full(fd, interpreter, length, (off_t) ph.p_offset) != 0) {
            rc = ELFPATCH_EIO;
            goto done;
        }
        if (length < ph.p_filesz) {
            const char zero = '\0';
            if (write_full(fd, &zero, 1, (off_t) ph.p_offset + (off_t) ph.p_filesz - 1) != 0) {
                rc = ELFPATCH_EIO;
                goto done;
            }
        }
    } else {
        /* Append the path to the end of the file and repoint the header at it. */
        struct stat st;
        if (fstat(fd, &st) != 0) {
            rc = ELFPATCH_EIO;
            goto done;
        }
        off_t offset = (off_t) st.st_size;
        if (write_full(fd, interpreter, length, offset) != 0) {
            rc = ELFPATCH_EIO;
            goto done;
        }

        ph.p_offset = (Elf64_Off) offset;
        ph.p_filesz = (Elf64_Xword) length;
        ph.p_memsz = (Elf64_Xword) length;
        if (write_full(fd, &ph, sizeof(ph), ph_offset) != 0) {
            rc = ELFPATCH_EIO;
            goto done;
        }
    }

    if (fsync(fd) != 0) {
        rc = ELFPATCH_EIO;
        goto done;
    }
    rc = ELFPATCH_OK;

done:
    close(fd);
    return rc;
}

const char *elfpatch_strerror(int code) {
    switch (code) {
        case ELFPATCH_OK:          return "success";
        case ELFPATCH_EINVAL:      return "invalid argument";
        case ELFPATCH_EOPEN:       return "the file could not be opened";
        case ELFPATCH_EIO:         return "a read or write failed";
        case ELFPATCH_EFORMAT:     return "not an ELF64 little-endian executable";
        case ELFPATCH_ENOINTERP:   return "the file has no PT_INTERP header";
        case ELFPATCH_ELONG:       return "the interpreter path is too long";
        default:                   return "unknown error";
    }
}
