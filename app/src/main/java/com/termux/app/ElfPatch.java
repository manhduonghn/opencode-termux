package com.termux.app;

/**
 * Thin Java wrapper around the native ELF PT_INTERP patcher ({@code libocpatch}).
 *
 * <p>The patcher lives in {@code app/src/main/cpp/elfpatch.c}. It replaces the external
 * {@code patchelf} tool, which had to be installed with apt, linked against libc++ and rewrote
 * the whole (up to ~250 MB) binary. The native patcher only rewrites the PT_INTERP program
 * header, so it is fast and cannot fail because of a missing libc++ symbol.</p>
 *
 * <p>The class is safe to reference on a device where the native library is missing: check
 * {@link #isAvailable()} before calling the native methods.</p>
 */
final class ElfPatch {

    /* Result codes, kept in sync with elfpatch.h. */
    static final int OK = 0;
    static final int EINVAL = -1;
    static final int EOPEN = -2;
    static final int EIO = -3;
    static final int EFORMAT = -4;
    static final int ENOINTERP = -5;
    static final int ELONG = -6;

    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("ocpatch");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private ElfPatch() {}

    /** @return true when the native patcher could be loaded. */
    static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Replace the interpreter (PT_INTERP) of an ELF64 file.
     *
     * @return {@link #OK} or one of the error codes
     */
    static native int setInterpreter(String path, String interpreter);

    /** @return the interpreter of an ELF64 file, or null when it cannot be read. */
    static native String getInterpreter(String path);

    private static native String errorString(int code);

    /** @return a human readable description of a result code. */
    static String describe(int code) {
        try {
            String text = errorString(code);
            if (text != null) return text;
        } catch (Throwable ignored) {
            // The native library is not available, fall back to the number.
        }
        return "error " + code;
    }
}
