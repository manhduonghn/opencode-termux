/*
 * JNI bridge between com.termux.app.ElfPatch and the ELF PT_INTERP patcher.
 */

#include <jni.h>

#include "elfpatch.h"

JNIEXPORT jint JNICALL
Java_com_termux_app_ElfPatch_setInterpreter(JNIEnv *env, __attribute__((__unused__)) jclass clazz,
                                            jstring path, jstring interpreter) {
    if (path == NULL || interpreter == NULL) return ELFPATCH_EINVAL;

    const char *nativePath = (*env)->GetStringUTFChars(env, path, NULL);
    const char *nativeInterpreter = (*env)->GetStringUTFChars(env, interpreter, NULL);

    jint result = ELFPATCH_EINVAL;
    if (nativePath != NULL && nativeInterpreter != NULL)
        result = (jint) elf_set_interpreter(nativePath, nativeInterpreter);

    if (nativeInterpreter != NULL) (*env)->ReleaseStringUTFChars(env, interpreter, nativeInterpreter);
    if (nativePath != NULL) (*env)->ReleaseStringUTFChars(env, path, nativePath);

    return result;
}

JNIEXPORT jstring JNICALL
Java_com_termux_app_ElfPatch_getInterpreter(JNIEnv *env, __attribute__((__unused__)) jclass clazz,
                                            jstring path) {
    if (path == NULL) return NULL;

    const char *nativePath = (*env)->GetStringUTFChars(env, path, NULL);
    if (nativePath == NULL) return NULL;

    char buffer[4096];
    jstring result = NULL;
    if (elf_get_interpreter(nativePath, buffer, sizeof(buffer)) == ELFPATCH_OK)
        result = (*env)->NewStringUTF(env, buffer);

    (*env)->ReleaseStringUTFChars(env, path, nativePath);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_termux_app_ElfPatch_errorString(JNIEnv *env, __attribute__((__unused__)) jclass clazz,
                                         jint code) {
    return (*env)->NewStringUTF(env, elfpatch_strerror((int) code));
}
