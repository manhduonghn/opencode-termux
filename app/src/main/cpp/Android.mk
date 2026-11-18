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

# The command line twin of the patcher, installed as $PREFIX/bin/oc-patch.
#
# Android only installs files called "lib*.so" from an APK, while ndk-build
# refuses to give an executable such a name ("LOCAL_MODULE_FILENAME must not
# contain a file extension"). Build the executable under its plain name and
# install a copy called "libocpatch-exec.so" next to it. That is the name the
# Android build packs into lib/<abi>/ and the app copies to $PREFIX/bin/oc-patch.
include $(CLEAR_VARS)
LOCAL_MODULE := ocpatch-exec
LOCAL_SRC_FILES := elfpatch.c ocpatch_main.c
include $(BUILD_EXECUTABLE)

ocpatch-exec-installed := $(LOCAL_INSTALLED)
ocpatch-exec-lib := $(dir $(ocpatch-exec-installed))libocpatch-exec.so

$(ocpatch-exec-lib): $(ocpatch-exec-installed)
	@echo "Install : libocpatch-exec.so <= ocpatch-exec"
	$(hide) $(call host-cp,$(ocpatch-exec-installed),$(ocpatch-exec-lib))

all: $(ocpatch-exec-lib)
