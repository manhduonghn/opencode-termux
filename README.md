# OpenCode for Termux

<p align="center">
  <img src="https://raw.githubusercontent.com/termux/termux-app/master/art/termux-icon.png" width="96" alt="Termux logo">
</p>

**OpenCode for Termux** is a Termux based Android application that runs the official
[OpenCode](https://opencode.ai) coding agent **natively** inside Termux — no root, no proot, no
chroot — and gives the agent the **full Termux environment**: shell, `apt`, compiler toolchain,
files in `$HOME`, `pkg`, `git`, `python`, … everything Termux can do, the agent can do.

The application is a fork of the official [Termux app](https://github.com/termux/termux-app) with
one addition: a bootstrapper that

1. installs the Termux **glibc runtime** (`glibc-runner`) and `patchelf`,
2. downloads the **official** OpenCode CLI from npm,
3. patches the ELF so that it runs on Android (`PT_INTERP` → Termux' glibc loader),
4. verifies the binary and installs it atomically,
5. starts the OpenCode background service and opens the in-app **web UI**,
6. opens the terminal/OpenTUI from the web toolbar with the `opencode` command when needed.

---

## Table of contents

- [How it works](#how-it-works)
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [The `opencode` and `oc-install` commands](#the-opencode-and-oc-install-commands)
- [Files and directories](#files-and-directories)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [Credits and license](#credits-and-license)

---

## How it works

OpenCode is published as a **glibc** ELF binary, Android only ships **bionic**. A glibc binary
cannot be executed on Android as is, it needs the glibc loader and the glibc C library.

Termux has an official glibc runtime (`glibc-repo` / `glibc-runner`) that installs
`$PREFIX/glibc/lib/ld-linux-<arch>.so.1`. The binary of OpenCode only needs its `PT_INTERP`
entry to be pointed to that loader:

```
apt-get install glibc-repo glibc-runner patchelf tar
  → GET https://registry.npmjs.org/@opencode/cli-linux-<arch>/-/<pkg>-<ver>.tgz
  → tar -xzf package/bin/opencode
  → patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-*.so.*
  → ./opencode --version            # must print the version
  → mv (atomic)  →  $HOME/.opencode/installed/opencode-v<ver>
  → ln -s         →  $HOME/.opencode/bin/opencode
  → $PREFIX/bin/opencode           # launcher script
```

Everything above happens inside **one Java class**
([`OpenCodeBootstrap.java`](app/src/main/java/com/termux/app/OpenCodeBootstrap.java)) so that the
installation keeps its progress, survives the app being killed in the middle of the download and
repairs itself afterwards.

> **Do not use `patchelf --set-rpath`.** It rewrites the dynamic section of the ~200 MB binary and
> makes it crash with `SIGSEGV` (exit code 139) even though the command reports success. Only the
> interpreter is patched — the glibc loader finds `libc.so.6` next to itself.

The full technical description, including the error matrix and the security trade-offs, is in
[`docs/OPENCODE_INSTALL_TECHNIQUE.md`](docs/OPENCODE_INSTALL_TECHNIQUE.md).

---

## Features

- **Web UI as the main screen** — the app opens the OpenCode web UI directly in an in-app WebView.
- **Terminal/OpenTUI as a secondary screen** — tap **Terminal** in the web toolbar to open a Termux
  session and run `opencode`; tap **Open Web UI** in the terminal menu to return to the web app.
- **Native OpenCode** — the official CLI, running as a normal process inside Termux.
- **Full Termux toolbox for the agent** — the agent runs in a normal Termux shell, so
  `apt install <anything>`, `python`, `node`, `git`, `clang`, … are available out of the box.
- **Automatic install** — the runtime is installed on the first start, with a progress dialog and
  a notification.
- **Self healing** — if the OpenCode auto updater replaces the patched binary, the app patches it
  again; the launcher does the same as a last resort.
- **Checksum verification** — the downloaded tarball is verified against the SHA-512 digest
  published by the npm registry.
- **No root, no proot** — only the Termux user space is used.
- **Floating input bar** — a dedicated text field to type prompts into the full screen TUI.
- **Keeps the device awake** — a wake lock is held while the agent is running.
- **Projects outside Termux** — open projects in shared device storage (`/storage/emulated/0`) or
  on an SD card. Grant access from **Settings → Bộ nhớ & SD card**, then tap **Chọn thư mục**.
- **Full file manager** — **Settings → Tệp & thư mục → Mở** browses `$HOME`, the shared device
  storage and SD cards. Open a folder as a project, open files with other apps, share one or more
  entries (folders are zipped automatically), rename, create, delete, search, sort and inspect
  sizes.
- **Share sheet integration** — the app appears in the Android share sheet. Files shared to it are
  copied to `~/Downloads` and opened in the file manager.

Supported ABIs: `arm64-v8a` and `x86_64` (the architectures OpenCode publishes builds for).

---

## Installation

Download the APK from the [releases](https://github.com/manhduonghn/opencode-termux/releases) or
build it yourself (see [Building from source](#building-from-source)).

> **Note** — the package name is still `com.termux`, so this app **replaces** the official Termux
> app. Uninstall the official Termux app first (or use a different package name in
> `app/build.gradle` if you want both, but then the Termux plugins will not work).

On the first start the app:

1. extracts the Termux bootstrap,
2. installs `glibc-runner`, `patchelf` and `tar` with `apt`,
3. downloads the OpenCode CLI (~100 MB),
4. patches, verifies and installs it,
5. starts the OpenCode service and opens the web UI. The terminal/OpenTUI is available from the
   **Terminal** button in the toolbar.

The installation needs roughly **1 GB of free space** (tarball + extracted binary + staging copy).

---

## Usage

Start the app: the OpenCode web UI opens. It talks to the same background service as the terminal.
Tap **Terminal** in the top bar to open a full Termux session; the session starts the OpenCode TUI
with the `opencode` command. The TUI and the web UI share the same OpenCode state, so a session can
be continued from either screen.

Useful things to do inside the terminal session:

```bash
pkg install python nodejs git clang   # the agent may install its own tools
opencode                              # start the agent again
opencode --version                    # check the installed version
oc-install                            # ask the app to (re)install the runtime
```

Long press the terminal to open the menu:

| Action | Description |
| --- | --- |
| **Open Web UI** | Return to the main web screen |
| **Restart OpenCode** | Interrupt the current process and start the TUI again |
| **Toggle the OpenCode input bar** | Show/hide the floating input field |
| **Install/Update OpenCode** | Download and install the latest version |

The floating input bar writes the text to the pty of the session using bracketed paste, so the
agent receives it exactly like a prompt typed in a real terminal.

### Projects on device storage or an SD card

OpenCode can open projects outside `$HOME`, for example in `/storage/emulated/0/Documents` or on
an SD card.

1. Open the gear menu → **Bộ nhớ & SD card** → **Cấp quyền**.
2. On Android 11+, enable **All files access** for the app. This is required to read and write the
   root of an SD card. If it is denied, the app falls back to the legacy storage permission, which
   still covers the primary shared storage. On Android 10 and lower the normal storage permission
   is enough, including for an SD card.
3. The app creates `~/storage/shared` (primary storage) and `~/storage/sdcard1`,
   `~/storage/sdcard2`, … (additional volumes).
4. Tap **Chọn thư mục** in the storage section, browse the internal storage or an SD card and
   select the project folder. The project opens in OpenCode and its path is copied to the
   clipboard. The picker only lists folders that Android actually allows the app to read.
5. Alternatively choose **Open Project** inside OpenCode and type one of the paths above (for
   example `~/storage/shared/Documents/my-project` or `/storage/emulated/0/Documents/my-project`)
   and press Enter. The picker resolves `~` and absolute paths, but it does not list symlinked
   directories, so type the path instead of browsing into `~/storage`.

If an SD card was inserted after the app started and does not appear, tap **Làm mới** in the
storage section. If it still does not appear, fully close and reopen the app.

### File manager and the Android share sheet

Repositories cloned from the GitHub section and other files written to `$HOME` accumulate over
time. Open the gear menu → **Tệp & thư mục** → **Mở** to browse the Termux home directory, the
shared device storage and an SD card.

- **Chạm** (tap) a folder to enter it, or a file to open it with a compatible app.
- **Nhấn giữ** (long press) to start a selection. Select as many entries as needed and use the
  toolbar to **share**, **delete** or **select all**.
- **⋮** opens the actions for one entry: open, **Mở làm dự án**, **Chia sẻ**, copy path, rename,
  details and delete.
- **↑ / ⌂** navigate to the parent directory or back to `~`. The path is clickable and copies the
  current directory.
- The toolbar also contains search, a hidden-files toggle, sorting (name, size, modification time)
  and **Thư mục mới** / **Tệp mới**.

**Sharing** uses the normal Android share sheet. Files are exposed through a non-exported
`FileProvider` with a temporary, per-URI read grant. Folders are zipped into the app cache first,
so a whole repository can be sent as one `.zip`.

The app is also a share target. When a file is shared to OpenCode from another app it is copied to
`~/Downloads` (name collisions get a ` (1)`, ` (2)`, … suffix) and the file manager opens at that
folder. Text shared without a file is saved as a `.txt` file.

Deleting is permanent and cannot be undone. The directories that Termux and the app need
(`$PREFIX`, `~/.opencode`, `~`, the storage roots) are protected and symlinks are deleted as links,
never by following their target.

---

## The `opencode` and `oc-install` commands

The app installs two commands into `$PREFIX/bin`:

- **`opencode`** — starts the TUI. It unsets `LD_PRELOAD` (the bionic preload of Termux must
  never be loaded into a glibc process), sets `OPENCODE_DISABLE_AUTOUPDATE=1` and re-patches the
  binary if the marker `.patched` does not match the installed file.
- **`oc-install`** — asks the app to install or update the runtime (it opens the app with the
  `com.termux.opencode.FORCE_UPDATE` intent). `opencode upgrade` is redirected to it, because only
  the app knows how to patch the binary again.

---

## Files and directories

| Path | Content |
| --- | --- |
| `~/.opencode/installed/opencode-v<ver>` | The patched binary |
| `~/.opencode/bin/opencode` | Symlink to the installed binary |
| `~/.opencode/.native-ready` | The version that is installed |
| `~/.opencode/.patched` | Fingerprint (`<size> <mtime>`) of the patched binary |
| `~/.opencode/setup.log` | Full log of every installation run |
| `~/.opencode/last-error.txt` | Report of the last failure |
| `~/.opencode/diagnostics.txt` | Snapshot of the environment |
| `$PREFIX/bin/opencode` | Launcher script |
| `$PREFIX/bin/oc-install` | Installer/update launcher script |
| `$PREFIX/glibc/lib/ld-linux-*.so.*` | The glibc loader used by the binary |

---

## Troubleshooting

| Symptom | Cause | Solution |
| --- | --- | --- |
| `opencode: not installed yet.` | The runtime is not installed | Open the app or run `oc-install` |
| `opencode: the glibc runtime is missing.` | `glibc-runner` was removed | Run `oc-install` |
| `invalid ELF header` / crash on start | `LD_PRELOAD` of Termux reached the glibc process | Always use `$PREFIX/bin/opencode`, never the binary in `~/.opencode/bin` directly |
| `Segmentation fault` (exit 139) | The binary was patched with `--set-rpath` | Run `oc-install`, the app only patches the interpreter |
| `cannot locate symbol __hash_memory` | `patchelf` is newer than `libc++` | The app reinstalls `patchelf` and `libc++` automatically |
| `dpkg was interrupted` | The app was killed during an install | The app runs `dpkg --configure -a` automatically |
| `None of the mirrors are accessible` | `pkg` could not reach any mirror | The app uses `apt-get` with its own list of mirrors |
| The download stops at some % | Slow or unstable network | Wait — reopen the app, the download continues where it stopped |

---

## Building from source

Requirements: JDK 17+, Android SDK with platform 36 and NDK `29.0.14206865`.

```bash
git clone https://github.com/manhduonghn/opencode-termux.git
cd opencode-termux
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease
```

The APKs are written to `app/build/outputs/apk/release/`.

To build only for a single ABI (much faster):

```bash
TERMUX_BUILD_ABIS=arm64-v8a ./gradlew assembleDebug
```

The application name can be changed without touching the package name:

```bash
TERMUX_APP_NAME="OpenCode Termux" ./gradlew assembleRelease
```

Every push to `master` also builds the `arm64-v8a` APK with GitHub Actions — download it from the
[Actions tab](https://github.com/manhduonghn/opencode-termux/actions).

See [`docs/BUILDING.md`](docs/BUILDING.md) for the details (signing, ABI splits, CI).

---

## Credits and license

- **Termux** — the terminal emulator and the whole Termux environment.
  <https://github.com/termux/termux-app> — GPL-3.0 (see [`LICENSE.md`](LICENSE.md)).
- **Termux packages** — the glibc runtime (`termux/glibc-packages`).
- **OpenCode** — the agent itself, <https://opencode.ai> — MIT.
- **patchelf** — <https://github.com/NixOS/patchelf> — GPL-2.0.

The installation technique is documented in
[`docs/OPENCODE_INSTALL_TECHNIQUE.md`](docs/OPENCODE_INSTALL_TECHNIQUE.md) and in
<https://github.com/manhduonghn/opencode-install-technique>.
