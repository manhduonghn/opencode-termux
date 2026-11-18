LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# The ELF PT_INTERP patcher, loaded by com.termux.app.ElfPatch.
include $(CLEAR_VARS)
LOCAL_MODULE := ocpatch
LOCAL_SRC_FILES := elfpatch.c ocpatch_jni.c
include $(BUILD_SHARED_LIBRARY)

# The command line twin of the patcher, installed as $PREFIX/bin/oc-patch. It is
# built as an executable but given a "lib*.so" name so that the Android build
# packs it into the APK and the app can copy it out.
include $(CLEAR_VARS)
LOCAL_MODULE := ocpatch-exec
LOCAL_MODULE_FILENAME := libocpatch-exec.so
LOCAL_SRC_FILES := elfpatch.c ocpatch_main.c
include $(BUILD_EXECUTABLE)
