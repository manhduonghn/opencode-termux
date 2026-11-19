# OpenCode for Termux

**OpenCode for Termux** is a Termux-based Android application that runs the official
[OpenCode](https://opencode.ai) coding agent natively inside Termux — no root, no proot, no
chroot — with access to the full Termux environment (shell, `apt`/`pkg`, toolchains, files in
`$HOME`, and device storage when granted).

This project is a fork of the [Termux app](https://github.com/termux/termux-app) with an
integrated bootstrapper and UI tailored for OpenCode.

## Features

- **Native OpenCode runtime** — downloads the official OpenCode CLI for the device ABI
  (`arm64-v8a` or `x86_64`) from npm, verifies it, and installs it for use inside Termux.
- **Runs glibc binaries on Android** — prepares the Termux glibc runtime and uses a native ELF
  `PT_INTERP` patcher (bundled in the app, no external `patchelf` dependency) so the official
  glibc build can run via Termux's glibc loader.
- **Web UI first** — opens the OpenCode web UI in-app; a **Terminal** action is available to run
  the OpenCode TUI in a Termux session against the same local service.
- **Automatic install & self-healing** — installs required runtime components on first start,
  tracks progress, and repairs/re-patches the installed binary if it is replaced (e.g. by an
  updater) so the launcher can keep working.
- **File manager** — browse `$HOME` and shared/device storage, manage files and folders, edit
  text files, open projects in OpenCode, handle archives, and share content through Android.
- **GitHub integration** — sign in and clone repositories from within the app.
- **Share target** — receive files/text shared from other Android apps.
- **No root / no proot** — runs in the normal Termux user space.

## How it works

1. Ensures the Termux bootstrap and required packages/runtime are available (including the glibc
   loader under `$PREFIX/glibc`). Packages are installed with Termux's `pkg`, which reuses cached
   package lists so a restart of the app skips redundant `apt update` calls.
2. Resolves the OpenCode release to install and downloads the official
   `@opencode-ai/cli-linux-<arch>` npm tarball.
3. Extracts the `opencode` binary, patches its ELF interpreter (`PT_INTERP`) to the Termux glibc
   loader using the bundled native patcher, verifies it can run, and installs it atomically under
   `~/.opencode/`.
4. Installs/maintains launcher helpers in `$PREFIX/bin` (e.g. `opencode`, `oc-install`,
   `oc-patch`) and starts the OpenCode service for the web UI / TUI.

Installation state, logs, and diagnostics are kept in `~/.opencode/` (see
[Troubleshooting](#troubleshooting)).

## Requirements

- Android device with a supported ABI for official OpenCode builds: **`arm64-v8a`** or
  **`x86_64`**.
- Sufficient free storage for Termux packages and the OpenCode runtime (roughly 1 GB recommended).
- Network access on first install/update.

> **Package name note:** this app currently uses the `com.termux` package name, so it may conflict
> with an existing Termux installation. Back up your data before installing if needed.

## Installation

- Download an APK from the repository's Releases page, or a CI build artifact from GitHub Actions
  (`Build arm64-v8a APK` workflow).
- Install the APK and launch the app. On first start it will prepare the runtime and install
  OpenCode, then open the web UI.

## Usage

- **Web UI:** the main screen connects to the local OpenCode service.
- **Terminal / OpenTUI:** use the in-app Terminal action to open a Termux session and run
  `opencode`.
- **Projects:** open folders as projects from the file manager (including shared storage paths when
  storage access is granted).
- **Files & sharing:** use the built-in file manager for browsing/editing/managing files; the app
  can also receive shared files/text from other apps.

Common commands inside Termux:

```sh
opencode --version   # show installed OpenCode version
opencode             # start OpenCode (TUI)
oc-install           # ask the app to (re)install/update the runtime
```

## Runtime files

| Path | Purpose |
| --- | --- |
| `~/.opencode/installed/opencode-v<ver>` | Installed OpenCode binary |
| `~/.opencode/bin/opencode` | Symlink to the active binary |
| `~/.opencode/.native-ready` | Installed version marker |
| `~/.opencode/.patched` | Fingerprint of the patched binary |
| `~/.opencode/setup.log` | Installer log |
| `~/.opencode/last-error.txt` | Last failure report |
| `~/.opencode/diagnostics.txt` | Environment/runtime diagnostics snapshot |
| `$PREFIX/bin/opencode` | Managed launcher script |
| `$PREFIX/bin/oc-install` | Managed install/update helper |
| `$PREFIX/bin/oc-patch` | Native ELF patcher helper (CLI) |

## Troubleshooting

- **Install/update fails:** check `~/.opencode/last-error.txt`, `~/.opencode/setup.log`, and
  `~/.opencode/diagnostics.txt` for details.
- **`opencode` not found or not runnable:** run `oc-install` (or reopen the app) to let the app
  repair/reinstall the runtime and launchers.
- **Binary replaced after an update:** the app/launcher can re-patch the binary automatically; if it
  still fails, run `oc-install`.
- **Unsupported device ABI:** official OpenCode builds are required; if your ABI is not supported,
  the runtime cannot be installed on that device.

## Building from source

See [docs/BUILDING.md](docs/BUILDING.md) for build requirements and instructions.

Quick example (single ABI debug build):

```sh
git clone <this-repo-url>
cd opencode-termux
TERMUX_BUILD_ABIS=arm64-v8a ./gradlew assembleDebug
```

## Credits and license

- Based on [Termux](https://github.com/termux/termux-app) (see [LICENSE.md](LICENSE.md)).
- Runs [OpenCode](https://opencode.ai).
- Uses the Termux glibc runtime/packages for executing glibc binaries on Android.

See also [termux-shared/LICENSE.md](termux-shared/LICENSE.md) for component licensing details.
