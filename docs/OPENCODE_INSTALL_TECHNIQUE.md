# Kỹ thuật cài đặt & khởi chạy OpenCode native trên Termux (viết bằng Java)

> **Bản xuất kỹ thuật (technical export).** Tài liệu này mô tả *đúng* những gì code trong repo này
> làm: tải các thư viện cần thiết (glibc runtime, `tar`), tải bản OpenCode chính thức,
> **vá `PT_INTERP`** của file ELF bằng một patcher native viết bằng C (không còn phụ thuộc
> `patchelf`), rồi **khởi chạy** — không cần root, không proot, không chroot.
>
> Mọi khẳng định kỹ thuật trong tài liệu đều truy được về mã nguồn; số dòng được ghi kèm để
> tiện tra cứu. Tài liệu này bổ sung cho [`FORK_OPENCODE.md`](./FORK_OPENCODE.md) (phần mô tả
> fork ở mức tổng quan) — ở đây đi sâu vào **kỹ thuật cài đặt**.

---

## 0. Tóm tắt (TL;DR)

Toàn bộ pipeline được điều khiển bởi **một file Java duy nhất**:
`app/src/main/java/com/termux/app/OpenCodeBootstrap.java`. Phần vá ELF được tách ra thành một thư
viện native nhỏ (`app/src/main/cpp/elfpatch.c`, nạp qua `ElfPatch.java`). Không có script shell nào
trong `assets/` — vì Termux nhúng `$PREFIX` vào binary của bootstrap, một script nằm sẵn trong
APK sẽ bị ghi đè/không tương thích khi cài đặt; đặt logic vào Java giữ được tính "một nguồn
sự thật" và cho phép app kiểm soát nhịp cài (tiến trình, notification, tiếp tục sau khi app bị
kill).

Pipeline 5 bước, mỗi bước là idempotent:

```
0. Gate          : $PREFIX/bin/apt-get phải tồn tại (Termux bootstrap đã giải nén) + second-stage + resolv.conf
1. Thư viện      : apt-get install glibc-repo → glibc-runner tar
2. Phiên bản     : GET https://opencode.ai/update/api/latest/cli/npm  (fallback: registry.npmjs.org)
3. Tải + giải nén: GET registry.npmjs.org/@opencode/cli-linux-<arch>/-/<pkg>-<ver>.tgz  →  tar -xzf
4. Vá ELF        : patcher native trỏ PT_INTERP tới $PREFIX/glibc/lib/ld-linux-*.so.*  (CHỈ interpreter)
5. Khởi chạy     : launcher $PREFIX/bin/opencode  (unset LD_PRELOAD, self-heal) + $PREFIX/bin/oc-install
```

Kết quả: `/data/data/com.termux/files/usr/bin/opencode` là binary glibc **chạy native** trên
Android (bionic làm kernel ABI, glibc làm userspace) — không cần root, không cần proot.

---

## 1. Bối cảnh: vì sao phải vá?

| Vấn đề | Chi tiết |
| --- | --- |
| OpenCode chỉ phát hành binary **glibc** | Bản npm `@opencode/cli-linux-arm64` / `-x64` được build cho glibc, không có bản musl/bionic. |
| Android chỉ có **bionic** | `libc.so` của Android là bionic; loader của Termux là `/system/bin/linker64`. Một binary glibc sẽ báo `not found` / `Exec format error` khi chạy trực tiếp. |
| Đường đi thay thế: **glibc-runner** | Repo chính thức `termux/glibc-packages` cung cấp loader `ld-linux-aarch64.so.1` + `libc.so.6` cho Termux trong `$PREFIX/glibc/lib`. |
| Binary vẫn cần **interpreter glibc** | ELF của OpenCode có `PT_INTERP = /lib64/ld-linux-x86-64.so.2` (đường dẫn của desktop Linux, không tồn tại trên Android). |
| ⇒ Cần **vá `PT_INTERP`** | Đổi *duy nhất* đường dẫn interpreter sang loader của Termux, giữ nguyên mọi thứ khác. Việc này do patcher native của app làm (xem §8). |

Sau khi vá, kernel chỉ cần đọc `PT_INTERP` và nạp loader của Termux; loader nạp `libc.so.6`,
`libpthread.so.0`… **từ chính thư mục của nó** (`$PREFIX/glibc/lib`) nên **không cần RPATH**.

### 1.1. Patcher native viết bằng C (thay cho `patchelf`)

Bản cũ dùng `patchelf --set-interpreter` (một gói apt riêng). Cách đó có ba nhược điểm:

1. **Phụ thuộc gói ngoài**: `patchelf` link với `libc++_shared.so`; nếu `libc++` trong `$PREFIX/lib`
   cũ hơn bản `patchelf` được build thì lệnh chết với `cannot locate symbol __hash_memory`, và
   **cả quá trình cài đặt thất bại**.
2. **Chậm**: `patchelf` ghi lại toàn bộ file ~250 MB, có thể mất hàng phút.
3. **Rủi ro hỏng file**: `patchelf --set-rpath` từng làm binary crash `SIGSEGV` (exit code **139**)
   dù báo thành công.

Bản hiện tại thay bằng `app/src/main/cpp/elfpatch.c`, được biên dịch vào APK (NDK) và nạp qua
`ElfPatch.java` (JNI). Nó **chỉ sửa header chương trình `PT_INTERP`**:

- Nếu đường dẫn mới **vừa** slot cũ (`strlen+1 <= p_filesz`) → ghi đè tại chỗ (vài byte, bất kể
  binary lớn cỡ nào), và bảo đảm byte cuối slot là `\0` (kernel kiểm tra điều này).
- Nếu **không vừa** → ghi chuỗi vào **cuối file** rồi trỏ lại `p_offset`/`p_filesz`/`p_memsz` của
  `PT_INTERP` vào đó. Kernel đọc interpreter trực tiếp từ file nên nó **không cần nằm trong một
  `PT_LOAD`** — đây là điểm mấu chốt khiến cách "append" hoạt động.

```c
/* elfpatch.c: elf_set_interpreter() */
if (length <= ph.p_filesz) {
    write_full(fd, interpreter, length, ph.p_offset);      /* ghi tại chỗ */
    write_full(fd, &zero, 1, ph.p_offset + ph.p_filesz - 1); /* byte cuối = NUL */
} else {
    off_t offset = st.st_size;                              /* nối vào cuối file */
    write_full(fd, interpreter, length, offset);
    ph.p_offset = offset; ph.p_filesz = length; ph.p_memsz = length;
    write_full(fd, &ph, sizeof(ph), ph_offset);
}
```

`RPATH` là thừa vì loader glibc của Termux tự tìm thư viện trong thư mục của nó.

> **Đã kiểm chứng thực tế**: chèn chuỗi interpreter dài hơn vào cuối một binary glibc, sửa header
> bằng `dd`, rồi chạy — binary khởi động bình thường qua loader mới. Vì vậy không cần `patchelf`.

### 1.2. Lỗi kinh điển: `LD_PRELOAD` của bionic

Termux preload `libtermux-exec.so` (một thư viện **bionic**) vào **mọi** tiến trình con. Nếu
binary glibc được exec với biến này còn nguyên, bionic lib sẽ bị nạp vào tiến trình glibc →
`invalid ELF header` / crash ngay.

⇒ **Luôn chạy qua `$PREFIX/bin/opencode`** (launcher có `unset LD_PRELOAD`), **không** chạy trực tiếp
`~/.opencode/bin/opencode` từ trong session Termux. Bản Java cũng tự gỡ `LD_PRELOAD`/`LD_LIBRARY_PATH`
khỏi môi trường lệnh (`OpenCodeBootstrap.java:579-580`).

---

## 2. Kiến trúc tổng thể

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Android App (com.termux)                                                │
│                                                                          │
│  OpenCodeWebActivity (màn hình chính) / TermuxActivity (terminal phụ)    │
│        │                                                                 │
│        ├─► TermuxInstaller.setupBootstrapIfNeeded ──► bootstrap $PREFIX  │
│        └─► OpenCodeBootstrap.setupIfNeeded(context, callback)            │
│                │                                                         │
│                ├─► Installer (thread "opencode-setup")                   │
│                │     1. apt-get  → glibc-repo, glibc-runner, tar         │
│                │     2. HTTP GET → tarball opencode (tiến trình %)        │
│                │     3. tar -xzf → ~/.opencode/tmp/package/bin/opencode  │
│                │     4. patch native (PT_INTERP) → staged file           │
│                │     5. verify (--version) → rename atomic → symlink     │
│                │     6. ghi $PREFIX/bin/{opencode,oc-install,oc-patch}   │
│                │                                                         │
│                └─► Notification (tiến trình / lỗi) + ProgressDialog      │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
        $PREFIX/glibc/lib/ld-linux-aarch64.so.1  ──►  ~/.opencode/installed/opencode-v<ver>
        (loader glibc của Termux)                        (binary glibc đã vá PT_INTERP)
                                    │
                    ┌───────────────┴────────────────┐
                    ▼                                ▼
        OpenCode service 127.0.0.1:49374      Session pty ──► "opencode\r"
        ──► WebView (màn hình chính)          ──► openTUI (màn phụ)
```

### 2.1. Các thành phần và đường dẫn

| Hằng số | Giá trị | Vai trò |
| --- | --- | --- |
| `PREFIX` | `/data/data/com.termux/files/usr` | Gốc Termux; chứa `bin/`, `glibc/lib/`. |
| `HOME` | `/data/data/com.termux/files/home` | `$HOME` của người dùng. |
| `OC_HOME` | `$HOME/.opencode` | Thư mục trạng thái của installer. |
| `INSTALL_DIR` | `$HOME/.opencode/installed` | Binary đã cài (`opencode-v<version>`). |
| `LINK_DIR` / `LINK_PATH` | `$HOME/.opencode/bin` / `…/bin/opencode` | **Symlink** trỏ tới binary đã cài. |
| `READY_MARKER_PATH` | `$HOME/.opencode/.native-ready` | Chứa **phiên bản** đã cài (nguồn sự thật cho "đã cài chưa"). |
| `PATCHED_MARKER_PATH` | `$HOME/.opencode/.patched` | Vân tay `"<size> <mtime>"` của binary **đã vá**. |
| `SETUP_LOG_PATH` | `$HOME/.opencode/setup.log` | Log chi tiết (append). |
| `ERROR_DETAIL_PATH` | `$HOME/.opencode/last-error.txt` | Báo cáo lỗi cuối cùng. |
| `DIAGNOSTICS_PATH` | `$HOME/.opencode/diagnostics.txt` | Snapshot môi trường mỗi lần chạy. |
| `APT_TMP_DIR` | `$HOME/.opencode/apt` | `sources.list` tạm cho mirror dự phòng. |
| `GLIBC_LIB_DIR` | `$PREFIX/glibc/lib` | Nơi loader glibc nằm. |
| `OPENCODE_BIN_PATH` | `$PREFIX/bin/opencode` | Launcher (script `sh`). |
| `OC_INSTALL_PATH` | `$PREFIX/bin/oc-install` | Launcher cập nhật. |
| `OC_PATCH_PATH` | `$PREFIX/bin/oc-patch` | Bản CLI của patcher native, launcher dùng để tự vá lại. |
| `SECOND_STAGE_SCRIPT_PATH` | `$PREFIX/etc/termux/termux-bootstrap/second-stage/…sh` | Bước 2 của bootstrap Termux. |
| `RESOLV_CONF_PATH` | `$PREFIX/etc/resolv.conf` | DNS (tạo nếu thiếu). |

### 2.2. Bản đồ mã nguồn trong repo

| File | Vai trò trong kỹ thuật này |
| --- | --- |
| `app/src/main/java/com/termux/app/OpenCodeBootstrap.java` | **Toàn bộ installer** (tải thư viện, tải opencode, vá, launcher, tự sửa, notification). |
| `app/src/main/java/com/termux/app/ElfPatch.java` | Cầu JNI nạp `libocpatch`, gọi patcher native. |
| `app/src/main/cpp/elfpatch.c` / `elfpatch.h` | Patcher `PT_INTERP` (thay `patchelf`): đọc/ghi interpreter của ELF64. |
| `app/src/main/cpp/ocpatch_jni.c` | Lớp JNI của `ElfPatch` (`setInterpreter`, `getInterpreter`, `errorString`). |
| `app/src/main/cpp/ocpatch_main.c` | CLI `oc-patch` (`set-interpreter`/`get-interpreter`), cài vào `$PREFIX/bin`. |
| `app/src/main/cpp/Android.mk` | Khai báo `libocpatch.so` (JNI) và `libocpatch-exec.so` (CLI). |
| `app/src/main/java/com/termux/app/OpenCodeWebActivity.java` | Màn hình chính: setup runtime nếu cần, mở web UI, nút mở terminal. |
| `app/src/main/java/com/termux/app/TermuxActivity.java` | Màn terminal phụ: nối installer vào vòng đời session, `checkRuntime`, intent `FORCE_UPDATE`. |
| `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java` | `startOpenCode()` — ghi `opencode\r` vào pty của session. |
| `app/src/main/java/com/termux/app/TermuxService.java` | Giữ service + wake lock để OpenCode sống tiếp khi app bị đẩy ra recents. |
| `app/src/main/java/com/termux/app/terminal/FloatingPromptInput.java` | Lớp phủ nhập liệu cho openTUI (ghi vào pty, không qua HTTP). |
| `app/src/main/res/values/strings.xml` | Chuỗi thông báo của installer. |

---

## 3. Giai đoạn 0 — Cổng chặn tiên quyết (gate)

Trước khi đụng vào `$PREFIX`, installer phải chắc chắn **bootstrap của Termux đã giải nén xong**:

```java
// OpenCodeBootstrap.java:363
private static boolean isTermuxBootstrapReady() {
    return new File(PREFIX + "/bin/apt-get").exists();
}
```

- Cổng này áp dụng cho **mọi** đường vào: `setupIfNeeded()` (dòng 222), `requestUpdate()` (dòng 347),
  `checkRuntime()` (dòng 1462).
- Lý do: `apt-get`, `tar` và cả `$PREFIX/bin/*` đều do bootstrap cung cấp. Chạy sớm sẽ
  ghi đè hoặc xung đột với chính trình cài bootstrap (race ⇒ `$PREFIX` hỏng ⇒ mất cả Termux).

### 3.1. Hai việc vệ sinh trước khi cài

**a) Hoàn tất second-stage của Termux** (`:635`)

```java
if (!new File(SECOND_STAGE_LOCK_PATH).exists())
    run(Arrays.asList(PREFIX + "/bin/bash", script.getAbsolutePath()), SECOND_STAGE_TIMEOUT_MS);
```

Second-stage của bootstrap cài `termux-tools` (trong đó có `login`, `pkg`…). Nếu lock file còn thì
coi như đã xong, không chạy lại.

**b) Bảo đảm có DNS** (`:650`)

```java
writeTextFile(new File(RESOLV_CONF_PATH), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
```

Chỉ ghi khi file thiếu **và** thư mục cha tồn tại. Không ghi đè cấu hình DNS của người dùng.

---

## 4. Giai đoạn 1 — Tải các thư viện cần thiết

### 4.1. Vì sao gọi `apt-get` chứ không gọi `pkg`

`pkg` không phải alias của `apt`: nó `source` `bin/termux-setup-package-manager` rồi chạy
`select_mirror`, vốn dò **~78 mirror song song** bằng `curl` (kèm symlink coreutils
`timeout`/`date`/`realpath`/`awk`). Trên mạng không tới được mirror nào, **mọi** lệnh `pkg` chết với
*"None of the mirrors are accessible"* trong khi `apt` vẫn chạy tốt với `sources.list` đã cấu hình.
`select_mirror` còn có thể **ghi đè** `sources.list` như tác dụng phụ.

⇒ Installer **không bao giờ** gọi `pkg`, không bao giờ sửa `sources.list`, `sources.list.d/*` hay
`/etc/profile.d/*` của người dùng.

### 4.2. Lệnh apt được dùng

```java
// OpenCodeBootstrap.java:948
$PREFIx/bin/apt-get \
  -o Acquire::Retries=3 \
  -o Acquire::http::Timeout=30 \
  -o Acquire::https::Timeout=30 \
  [-o Dir::Etc::sourcelist=$HOME/.opencode/apt/sources.list -o Dir::Etc::sourceparts=-] \
  <args…>
```

- `Acquire::Retries=3` + timeout 30 s: chịu được mạng chập chờn diện động.
- `Dir::Etc::sourcelist` trỏ vào file **tạm** ⇒ không bao giờ đụng cấu hình của người dùng.
- `Dir::Etc::sourceparts=-` ⇒ bỏ qua `sources.list.d/` (chặn kéo theo file của người dùng khi dùng
  mirror dự phòng).

### 4.3. Trình tự cài

```java
// OpenCodeBootstrap.java:459-478
aptUpdate(false)                      // làm mới package list
aptInstall(["glibc-repo"])             // thêm repo glibc chính thức + khóa ký
aptUpdate(true)                       // làm mới list của repo glibc
aptInstall(["glibc-runner","tar","termux-am","ripgrep"])
```

- `glibc-repo` là gói "siêu dữ liệu": nó thêm `deb https://packages-cf.termux.dev/apt/termux-glibc glibc stable`
  và khóa ký của repo vào `sources.list.d/`. Chỉ sau khi nó được cài, `apt update` mới "thấy" repo glibc.
- `glibc-runner` mang loader `ld-linux-*.so.*` + bộ `*-glibc` toolchain vào `$PREFIX/glibc`.
- `tar` = giải nén `.tgz`; `termux-am` = launcher `oc-install` gọi intent; `ripgrep` = tiện ích cho agent.
- **Không còn `patchelf`**: việc vá ELF do thư viện native đi kèm APK làm (xem §1.1 và §8).

### 4.4. Mirror dự phòng (không phụ thuộc `pkg`)

```java
// OpenCodeBootstrap.java:134
MAIN_MIRRORS = { packages-cf.termux.dev/apt/termux-main, packages.termux.dev/apt/termux-main,
                 grimler.se/termux-main, mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main }
GLIBC_MIRRORS = { packages-cf.termux.dev/apt/termux-glibc, packages.termux.dev/apt/termux-glibc }
```

Thứ tự thử (`aptUpdate`, dòng 661):

1. `apt update` với cấu hình của **người dùng** (không ghi đè gì). Thành công → dừng.
2. Thất bại → dựng `sources.list` tạm (`writeTempSources`, dòng 969) với từng cặp
   (main mirror × glibc mirror) và thử lại. Mỗi lần thử ghi log rõ mirror đang dùng.
3. Hết mirror → quay lại cấu hình gốc, trả `false` ⇒ installer báo lỗi *"Could not refresh the package
   lists from any mirror. Check the Wi-Fi/mobile connection and try again."*

Nội dung file tạm:

```
deb <main-mirror> stable main
deb <glibc-mirror> glibc stable     ← chỉ khi đang cần repo glibc
```

### 4.5. Tự sửa khi `apt` bị "dở dang"

Cài đặt bị giết đúng lúc `dpkg` đang giải nén ⇒ database package ở trạng thái nửa vời, và `apt-get`
từ chối làm gì cả (*"dpkg was interrupted"*) cho tới khi `dpkg --configure -a` chạy một lần.

```java
// OpenCodeBootstrap.java:714
if (apt(args, APT_INSTALL_TIMEOUT_MS)) return true;      // 1) thử cài
run([dpkg, "--configure", "-a"], APT_INSTALL_TIMEOUT_MS);  // 2) vá database
apt(["-f","install","-y"], APT_INSTALL_TIMEOUT_MS);       // 3) sửa dependency
return apt(args, APT_INSTALL_TIMEOUT_MS);                 // 4) thử lại
```

### 4.6. Cài patcher native vào `$PREFIX/bin/oc-patch`

Patcher là một thư viện native **nằm sẵn trong APK** (`libocpatch-exec.so`), không cần `apt` và
không thể "hỏng" vì thiếu `libc++`:

```java
// OpenCodeBootstrap.java: installOcPatch()
File source = new File(nativeLibraryDir, "libocpatch-exec.so");
copyFile(source, new File(PREFIX + "/bin/oc-patch"));
Os.chmod(PREFIX + "/bin/oc-patch", 0755);
```

- Việc copy là idempotent: nếu đích đã tồn tại, thực thi được và cùng kích thước thì bỏ qua.
- JNI `libocpatch.so` được `System.loadLibrary("ocpatch")` nạp; nếu nạp thất bại (ví dụ APK hỏng)
  installer **fail ngay** với thông báo *"The native ELF patcher could not be loaded"* — trước khi
  tải 250 MB.

> Nhờ vậy bước "công cụ vá có chạy được không" biến mất: không có gói ngoài nào để hỏng.

### 4.7. Tìm loader glibc

```java
// OpenCodeBootstrap.java:1686
for (File f : new File(PREFIX + "/glibc/lib").listFiles())
    if (f.getName().startsWith("ld-linux") && f.getName().contains(".so.") && f.canExecute())
        return f.getAbsolutePath();
```

Điều kiện `canExecute()` loại bỏ file loader không có quyền thực thi. Không tìm thấy ⇒ fail install
(kèm đường dẫn đã tìm).

---

## 5. Giai đoạn 2 — Xác định phiên bản OpenCode

Hai nguồn, theo thứ tự ưu tiên (`fetchLatestVersion`, dòng 1014):

| # | Nguồn | URL | Ghi chú |
| --- | --- | --- | --- |
| 1 | API chính thức | `https://opencode.ai/update/api/latest/cli/npm` | Trả JSON; bắt `"version"\s*:\s*"([^"]+)"`. |
| 2 | npm registry | `https://registry.npmjs.org/@opencode/cli-linux-<arch>/latest` | Dùng khi API không trả lời (offline/VPN). |

```java
private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
```

Nếu cả hai fail ⇒ *"Could not determine the OpenCode version (check the network)."*

### 5.1. Ánh xạ ABI → tên package npm

```java
// OpenCodeBootstrap.java:1669
arm64*            → "arm64"   → @opencode/cli-linux-arm64
x86_64 | amd64    → "x64"     → @opencode/cli-linux-x64
(khác)            → null      → fail: "CPU architecture \"<abi>\" has no official OpenCode build"
```

Chỉ `arm64-v8a` và `x86_64` được OpenCode phát hành ⇒ các ABI 32-bit (armeabi-v7a, x86) **không
dùng được** và bị báo lỗi rõ ràng thay vì tải về một thứ không chạy.

---

## 6. Giai đoạn 3 — Tải tarball OpenCode

### 6.1. URL nguồn

```java
// OpenCodeBootstrap.java:1031
file = "cli-linux-" + arch + "-" + version + ".tgz";
url1 = https://registry.npmjs.org/@opencode/cli-linux-<arch>/-/<file>
url2 = https://registry.npmjs.org/@opencode-ai/cli-linux-<arch>/-/<file>   // fallback scope
```

Nếu url1 fail (404 vì đổi scope trên npm) ⇒ tự động thử url2 và ghi log
*"The first source failed, trying the alternative npm package name"*.

### 6.2. HTTP thuần, tự xử lý redirect

Không dùng thư viện ngoài — `HttpURLConnection` để có thể báo % tiến trình:

```java
// OpenCodeBootstrap.java:1160
connection.setInstanceFollowRedirects(false);      // tự điều khiển
for (hop = 0; hop < 6; hop++) {
    code = getResponseCode();
    if (code == 301|302|303|307|308) { current = new URL(new URL(current), location); continue; }
    return connection;
}
throw new IOException("too many redirects for " + url);
```

Bội số: `setInstanceFollowRedirects(false)` + tối đa 6 chặng, `Location` được resolve tương đối so với
URL hiện tại (`new URL(new URL(current), location)`) để không vỡ với redirect tương đối.

Timeout: connect 20 s, read 60 s. User-Agent `Termux-OpenCode`.

### 6.3. Báo tiến trình (2 % hoặc 3 giây)

```java
// OpenCodeBootstrap.java:1069-1088
int percent = (int)(read * 100 / total);
boolean step = percent >= lastPercent + 2 || percent == 100;
if (percent != lastPercent && (step || now - lastReportAt >= 3000))
    status("Downloading OpenCode " + version + ": " + percent + "% (" + mib + "/" + total + " MB, "
        + rate + " MB/s, " + eta + ")");
```

Buffer 64 KiB; nếu server không trả `Content-Length` thì chỉ báo "đã tải X MB (Y MB/s)".

Câu chữ này chính là thứ người dùng thấy trong notification *"OpenCode: downloading"* và trong
dialog khi mở lại app giữa chừng.

---

## 7. Giai đoạn 4 — Giải nén

```java
// OpenCodeBootstrap.java:840
run([PREFIX + "/bin/tar", "-xzf", tarball, "-C", tmpDir, "package/bin/opencode"], 180_000);
```

- Chỉ giải nén **đúng một thành phần** `package/bin/opencode` (không giải nén cả gói ⇒ nhanh, ít dung lượng).
- Thư mục làm việc: `$HOME/.opencode/tmp` (xoá sạch trước mỗi lần tải).
- Fail ⇒ dọn `tmp` và báo *"extracting the tarball failed (exit N)"*.

---

## 8. Giai đoạn 5 — Vá ELF (trái tim của kỹ thuật)

### 8.1. Quy trình "staged + atomic"

```
~/.opencode/tmp/package/bin/opencode          (file vừa giải nén)
        │  rename() (ưu tiên, cùng filesystem) hoặc copy() (fallback)
        ▼
~/.opencode/installed/.opencode-v<ver>.new     (file tạm để vá)
        │  elf_set_interpreter() → PT_INTERP = $PREFIX/glibc/lib/ld-linux-*.so.*
        │  chmod 0755        (bảo đảm bit thực thi)
        │  ./file --version   (KIỂM CHỨNG: phải chạy được và in ra số phiên bản)
        ▼
~/.opencode/installed/opencode-v<ver>          (rename atomic)
        │  ghi $HOME/.opencode/.native-ready = "<ver>"
        ▼
~/.opencode/bin/opencode                      (symlink → file trên)
```

Điểm mấu chốt:

| Cơ chế | Vì sao |
| --- | --- |
| Vá trên **file tạm**, không vá file đang chạy | Không bao giờ thay binary "sống" dưới chân tiến trình đang dùng. |
| `rename()` atomic trong cùng filesystem | Lần chạy app cũ luôn thấy binary cũ **hoặc** binary mới, không bao giờ thấy file nửa vời. |
| Copy fallback khi `rename` thất bại | `tmp` và `installed` có thể nằm khác filesystem ⇒ `rename` trả `EXDEV`. |
| `--version` **phải chạy được** mới được cài | Bắt được binary hỏng (vd. do `--set-rpath` ở bản cũ) trước khi nó thành bản cài chính thức. |
| Nếu verify fail nhưng **đã có** bản cài tốt | Giữ nguyên bản cũ, chỉ cảnh báo (*"keeping the installed one"*). |

```java
// OpenCodeBootstrap.java:879-903
CmdResult verify = verifyBinary(staged);            // staged --version
boolean verified = looksLikeAVersion(verify);        // exit 0 && /\d+\.\d+/
if (verified) { rename → target; ghi .native-ready; }
else if (!target.isFile()) { fail; }                 // chưa có gì cài → fail
else { status("WARNING: the downloaded build does not run ..., keeping the installed one"); }
```

`looksLikeAVersion` dùng regex `(?s).*\\d+\\.\\d+.*` ⇒ in ra số phiên bản là bằng chứng nhịp đập.

### 8.2. Vá bằng JNI, không cần retry

```java
// OpenCodeBootstrap.java: patchInterpreter()
int code = ElfPatch.setInterpreter(file.getAbsolutePath(), loader);
if (code != ElfPatch.OK) → fail (kèm ElfPatch.describe(code))
```

Vì patcher native chỉ sửa header (vài byte), không có gói ngoài nào để hỏng và **không cần cơ chế
retry** như bản `patchelf` cũ. Không còn timeout 300 s: thao tác gần như tức thời bất kể binary lớn
cỡ nào.

### 8.3. Marker `.patched` — vì sao chỉ ghi khi thật sự đã vá

```java
// OpenCodeBootstrap.java: installBinary()
CmdResult verify = run([staged, "--version"], VERIFY_TIMEOUT_MS);   // staged đã vá
if (looksLikeAVersion(verify)) { rename → target; ensurePtyHelper(); }
```

Marker `.patched` được ghi bởi `repairInterpreterIfNeeded()` sau khi bản vá lại chạy được:

```java
// OpenCodeBootstrap.java:1633-1673
if (binaryStamp(binary).equals(readTrimmed(new File(PATCHED_MARKER_PATH)))) return; // đã vá
... vá trên bản sao → chạy --version → rename atomic ...
writeTextFileQuietly(new File(PATCHED_MARKER_PATH), binaryStamp(binary));
```

Vân tay lưu là:

```java
binaryStamp(file) = file.length() + " " + (file.lastModified() / 1000L);   // "<size> <mtime>"
```

Launcher dùng cùng định dạng: `stat -L -c '%s %Y'`.

> Nếu ghi marker khi **chưa** vá, launcher sẽ `exec` binary chưa vá ⇒ `not found`/crash. Đó là lý do
> marker chỉ được ghi sau khi binary đã vá **và** `--version` chạy được. (JNI `ElfPatch.getInterpreter`
> và CLI `oc-patch get-interpreter` cho phép đọc lại `PT_INTERP` để kiểm tra thủ công.)

---

## 9. Giai đoạn 6 — Khởi chạy (launcher)

Installer ghi hai script vào `$PREFIX/bin` (quyền `0755`):

### 9.1. `$PREFIX/bin/opencode`

Nội dung (rút gọn từ `buildOpenCodeLauncher()`, dòng 1368):

```sh
#!/data/data/com.termux/files/usr/bin/sh
# Managed by the OpenCode for Termux app. opencode-launcher-version: 5
P="${PREFIX:-/data/data/com.termux/files/usr}"
H="${HOME:-/data/data/com.termux/files/home}"
PATH="$P/bin:$PATH"; export PATH

case "${1:-}" in                 # opencode upgrade|update → nhờ app cập nhật
  upgrade|update) exec "$P/bin/oc-install" ;;
esac

BIN="$H/.opencode/bin/opencode"
GLIBCLIB="$P/glibc/lib"
PATCHED="$H/.opencode/.patched"

[ -x "$BIN" ] || { echo "opencode: not installed yet. ..." >&2; exit 127; }
LOADER=""; for c in "$GLIBCLIB"/ld-linux*.so.*; do [ -x "$c" ] && { LOADER="$c"; break; }; done
[ -z "$LOADER" ] && { echo "opencode: the glibc runtime is missing. ..." >&2; exit 127; }

export OPENCODE_DISABLE_AUTOUPDATE=1     # chặn updater của chính OpenCode ghi đè binary đã vá
unset LD_PRELOAD                          # gỡ lib bionic của Termux
unset LD_LIBRARY_PATH

MARK=$(cat "$PATCHED" 2>/dev/null)
NOW=$(stat -L -c '%s %Y' "$BIN" 2>/dev/null)
if [ -z "$MARK" ] || [ "$MARK" != "$NOW" ]; then
  # Binary đã đổi sau lần vá cuối → vá lại trên BẢN SAO, kiểm tra nó chạy, rồi mới mv
  REAL=$(readlink -f "$BIN" 2>/dev/null || echo "$BIN"); TMP="$REAL.repair.$$"
  if [ -x "$P/bin/oc-patch" ] \
     && cp "$REAL" "$TMP" 2>/dev/null \
     && "$P/bin/oc-patch" set-interpreter "$LOADER" "$TMP" >/dev/null 2>&1 \
     && chmod 755 "$TMP" 2>/dev/null \
     && "$TMP" --version >/dev/null 2>&1 \
     && mv "$TMP" "$REAL" 2>/dev/null; then
    echo "$(stat -L -c '%s %Y' "$BIN")" > "$PATCHED"
    exec "$BIN" "$@"
  fi
  rm -f "$TMP" 2>/dev/null
  echo "opencode: this build is not patched yet; running it through the glibc loader ..." >&2
  exec "$LOADER" --library-path "$GLIBCLIB" "$BIN" "$@"
fi

exec "$BIN" "$@"
```

Các quyết định thiết kế đáng chú ý:

| Quyết định | Lý do |
| --- | --- |
| `exec` thay vì fork | Giữ đúng 1 tiến trình, tín hiệu/PTY đi thẳng tới openTUI. |
| `unset LD_PRELOAD` | Không nạp `libtermux-exec.so` (bionic) vào tiến trình glibc. |
| `OPENCODE_DISABLE_AUTOUPDATE=1` | Updater của OpenCode sẽ thay binary bằng bản chưa vá ⇒ app không còn vá lại được. |
| Marker `.patched` khớp ⇒ `exec` thẳng | Đường nóng: không có loader ở giữa, khởi động nhanh nhất. |
| Marker lệch ⇒ **vá trên bản sao** | Không phá binary đang hoạt động nếu `oc-patch` hỏng. |
| Fallback `exec "$LOADER" --library-path ...` | Luôn có đường chạy được kể cả khi không vá được (chậm hơn nhưng sống). |
| `upgrade` \| `update` → `oc-install` | Khiến lệnh update của chính OpenCode đi qua app để vá lại. |

### 9.2. `$PREFIX/bin/oc-install`

```sh
#!/data/data/com.termux/files/usr/bin/sh
# Managed by the OpenCode for Termux app. opencode-launcher-version: 5
P="${PREFIX:-/data/data/com.termux/files/usr}"; AM="$P/bin/am"
[ -x "$AM" ] || { echo "oc-install: the 'am' command is missing (termux-am package). ..." >&2; exit 127; }
echo "oc-install: asking the OpenCode app to install/update the runtime (watch the notification)"
exec "$AM" start -a android.intent.action.VIEW \
  -n com.termux/com.termux.app.OpenCodeWebActivity \
  --ez com.termux.opencode.FORCE_UPDATE true >/dev/null 2>&1
```

⇒ Việc cập nhật **luôn đi qua app**, vì app mới là thứ biết cách vá, xác minh, ghi marker.

### 9.3. "Quản lý" launcher bằng marker

```java
// OpenCodeBootstrap.java:118
private static final String LAUNCHER_MARKER =
    "Managed by the OpenCode for Termux app. opencode-launcher-version: 5";
```

`restoreLauncher()` chỉ ghi lại launcher nếu file hiện tại **không** chứa marker ⇒ launcher do
build cũ (hoặc do script installer đã bị gỡ) được thay thế, còn launcher đúng thì không đụng.

### 9.4. Khởi chạy trong app (session pty)

```java
// TermuxTerminalSessionActivityClient.java:408
public void startOpenCode(@Nullable final TerminalSession session) {
    ...
    byte[] command = "opencode\r".getBytes(StandardCharsets.UTF_8);
    session.write(...)
}
```

- App **chỉ tạo một session** (`addNewSession` ghim vào session sẵn có) và tự gõ `opencode\r` vào pty.
- Nút "Restart OpenCode" gọi `restartOpenCodeSession()` → `startOpenCode(currentSession)`.
- App giữ wake lock (partial + Wi-Fi) và `START_STICKY`; `onTaskRemoved` khởi động lại service ⇒
  OpenCode sống tiếp khi app bị đẩy khỏi recents.
- Người dùng nhập liệu trong openTUI qua **floating prompt input** (`FloatingPromptInput`), ghi vào
  đúng pty đó bằng bracketed paste `\x1b[200~…\x1b[201~` + `\r` — không có HTTP API nào.

---

## 10. Giai đoạn 7 — Tự sửa chữa & idempotency

### 10.1. Kiểm tra "runtime còn đủ không"

```java
// OpenCodeBootstrap.java:208
private static List<String> runtimeProblems() {
    problems.add(READY_MARKER_PATH)   nếu thiếu  (.native-ready)
    problems.add(OPENCODE_BIN_PATH)   nếu thiếu  ($PREFIX/bin/opencode)
    problems.add(OC_INSTALL_PATH)     nếu thiếu  ($PREFIX/bin/oc-install)
    problems.add(LINK_PATH)           nếu thiếu  ($HOME/.opencode/bin/opencode)
    problems.add(GLIBC_LIB_DIR + "/ld-linux-*.so.*") nếu không tìm thấy loader
    problems.add(PREFIX + "/bin/oc-patch") nếu không executable
    problems.add(PREFIX + "/bin/tar")      nếu không executable
}
```

Đây là kiểm tra **"cả runtime"**, không chỉ "đã cài version nào". Hệ quả: app bị đóng giữa lúc
tải ⇒ lần mở sau sẽ **cài lại đúng phần còn thiếu**, tái dùng binary đã tải & đã verify (không tải
lại 90 MB).

### 10.2. Ba điểm vào của cơ chế tự sửa

| Điểm vào | Khi nào | Hành động |
| --- | --- | --- |
| `setupIfNeeded()` (dòng 221) | Cold start, session rỗng | Nếu thiếu gì ⇒ chạy installer; nếu đủ ⇒ thread nền: dọn script installer cũ, vá lại nếu binary đổi, dọn notification "đang tải" bị bỏ rơi. |
| `checkRuntime()` (dòng 1456) | **Mỗi lần** app lên foreground | Rẻ (vài lệnh `stat`); nếu phát hiện thiếu ⇒ gọi `setupIfNeeded()`. |
| Launcher `opencode` | Mỗi lần chạy lệnh | Tự vá lại nếu marker lệch, fallback qua loader nếu không vá được. |

```java
// OpenCodeBootstrap.java:305 — repairInterpreterIfNeeded
if (binaryStamp(binary).equals(readTrimmed(new File(PATCHED_MARKER_PATH)))) return;  // đã vá
Installer installer = new Installer(activity);
installer.setLogOnly();              // chỉ ghi log, không spam notification
installer.repairBinary(binary, loader);
```

`repairBinary` (dòng 790) lặp lại đúng quy trình an toàn: **copy → vá → chmod → `--version` → rename**.
Nếu bất kỳ bước nào fail ⇒ xoá file tạm, **giữ nguyên binary cũ**, và in cảnh báo *"OpenCode runs
through the glibc loader"*.

### 10.3. Dọn dẹp installer cũ

```java
// OpenCodeBootstrap.java:262
deleteQuietly(new File(LEGACY_SETUP_SCRIPT_PATH));        // ~/.opencode/setup.sh (script cũ)
restoreLauncher(new File(OPENCODE_BIN_PATH), buildOpenCodeLauncher());
restoreLauncher(new File(OC_INSTALL_PATH), buildOcInstallLauncher());
```

⇒ **một đường cài duy nhất**: Java. Không còn script shell nào có thể cài song song và gây xung đột.

### 10.4. Khi app bị kill giữa lúc cài

- Notification "đang tải" (ongoing) bị hệ thống giữ lại ⇒ lần mở sau app phải **xoá** nó khi runtime
  đã đủ và không có install nào đang chạy (`cancelSetupProgressNotification`, dòng 1636).
- Install đang chạy ⇒ mọi activity mở ra đều thấy dialog tiến trình
  (`showInstallProgressIfRunning`, dòng 1479) — không cảm giác "app treo".
- Yêu cầu thứ hai tới khi đang cài ⇒ **không** chạy song song; callback được xếp hàng và chạy khi
  install kết thúc (`sInstallWaiters`, dòng 1539) ⇒ session không bao giờ mở khi runtime chưa sẵn sàng.

### 10.5. Luồng kiểm soát trong app

```
OpenCodeWebActivity (launcher, web là màn hình chính)
  └─ TermuxInstaller.setupBootstrapIfNeeded(...)      // bootstrap Termux ($PREFIX)
       └─ OpenCodeBootstrap.setupIfNeeded(context, cb)
            ├─ runtimeProblems() rỗng ?  → thread nền: bảo trì + cb() ngay
            └─ không rỗng           → Installer (thread "opencode-setup", cb=null)
                                          progress: ProgressDialog + Notification
                                          kết thúc: notifyFinished + runInstallWaiters
  └─ InstallListener → load web UI (OpenCode service + pair link + cookie)

TermuxActivity (terminal phụ, mở từ nút Terminal)
  └─ onServiceConnected
       └─ TermuxInstaller.setupBootstrapIfNeeded(...)
            └─ OpenCodeBootstrap.setupIfNeeded(activity, cb)
  └─ cb(): addNewSession(...) → startOpenCode(session) → ghi "opencode\r" vào pty
```

---

## 11. Môi trường thực thi của các lệnh con

```java
// OpenCodeBootstrap.java:566-587
Map<String,String> shellEnv = new TermuxShellEnvironment().getEnvironment(activity, false);
env.putAll(shellEnv);
env.put("TERM","xterm-256color"); env.put("LANG","en_US.UTF-8"); env.put("COLORTERM","truecolor");
env.put("DEBIAN_FRONTEND","noninteractive");
env.put("TERMUX_PKG_NO_MIRROR_SELECT","1");     // không cho maintainer script đổi mirror
env.remove("LD_PRELOAD"); env.remove("LD_LIBRARY_PATH");
```

Khi chạy tiến trình con:

```java
// OpenCodeBootstrap.java:1203
builder.directory(new File(HOME));
builder.redirectErrorStream(true);
processEnv.clear(); processEnv.putAll(env);     // env sạch, không kế thừa env của app
```

- `ProcessBuilder` với `env.clear()` ⇒ lệnh con **không** kế thừa môi trường của app Android (rất
  bẩn), chỉ nhận đúng những biến đã chọn.
- `redirectErrorStream(true)` ⇒ stdout+stderr gộp, dễ đọc log.
- Thread riêng `"opencode-cmd-reader"` đọc output ⇒ không bị nghẽn pipe.
- `waitFor()` có timeout (dòng 1258): hết thời gian ⇒ `destroy()` ⇒ đợi 250 ms ⇒ trả `-1` nếu vẫn
  chưa chết (⇒ coi như lỗi). Với `apt-get` nên dùng `streamOutput=true` để log từng dòng
  (`Get:1 … glibc-runner_…deb`, `Fetched 1.2 MB in 3s`).

### 11.1. Bảng thời gian chờ

| Hằng số | Giá trị | Dùng cho |
| --- | --- | --- |
| `HTTP_CONNECT_TIMEOUT_MS` | 20 s | Kết nối HTTP. |
| `HTTP_READ_TIMEOUT_MS` | 60 s | Đọc HTTP. |
| `APT_UPDATE_TIMEOUT_MS` | 240 s | `apt-get update`. |
| `APT_INSTALL_TIMEOUT_MS` | 900 s | `apt-get install` (gói glibc khá lớn). |
| `SECOND_STAGE_TIMEOUT_MS` | 240 s | Script second-stage của Termux. |
| `VERIFY_TIMEOUT_MS` | 90 s | `oc-patch --version`, `opencode --version`. |
| (cứng) | 180 s | `tar -xzf`. |

### 11.2. Bộ nhớ đệm & giới hạn log

| Hằng số | Giá trị | Ý nghĩa |
| --- | --- | --- |
| `MAX_OUTPUT_CHARS` | 256 KiB | Output của một lệnh được giữ trong RAM (phần còn lại bị bỏ). |
| `MAX_STEPS` | 40 | Số bước gần nhất giữ để dựng báo cáo lỗi. |
| `FAILURE_LOG_LINES` | 14 dòng | Số dòng log/diagnostics đuôi ghi vào báo cáo lỗi. |
| `MAX_DETAIL_CHARS` | 900 | Độ dài tối đa của phần chi tiết trong notification. |

---

## 12. Đối chiếu Java ↔ shell (bản tra cứu nhanh)

| Bước | Java (`OpenCodeBootstrap.java`) | Tương đương shell |
| --- | --- | --- |
| Gate | `isTermuxBootstrapReady()` (`:363`) | `[ -x "$PREFIX/bin/apt-get" ]` |
| Second-stage | `ensureSecondStage()` (`:635`) | `bash $PREFIX/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh` |
| DNS | `ensureResolvConf()` (`:650`) | `printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > $PREFIX/etc/resolv.conf` |
| Bản chữa | `aptUpdate(false)` (`:661`) | `apt-get update -y` |
| Repo glibc | `aptInstall(["glibc-repo"])` (`:466`) | `apt-get install -y glibc-repo` |
| Refresh glibc | `aptUpdate(true)` (`:470`) | `apt-get update -y` |
| Thư viện | `aptInstall(["glibc-runner","tar","termux-am","ripgrep"])` | `apt-get install -y glibc-runner tar termux-am ripgrep` |
| Patcher | `installOcPatch()` + `ElfPatch.isAvailable()` | giải nén `libocpatch-exec.so` từ APK → `$PREFIX/bin/oc-patch` |
| Phiên bản | `fetchLatestVersion()` (`:1014`) | `curl -s https://opencode.ai/update/api/latest/cli/npm \| grep -o '"version":"[^"]*"'` |
| Tải | `downloadTarball()` (`:1031`) | `curl -L -o oc.tgz https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-<ver>.tgz` |
| Giải nén | `installBinary()` (`:840`) | `tar -xzf oc.tgz -C tmp package/bin/opencode` |
| Vá | `patchInterpreter()` → `ElfPatch.setInterpreter()` | `oc-patch set-interpreter $PREFIX/glibc/lib/ld-linux-aarch64.so.1 tmp/package/bin/opencode` |
| Xác minh | `verifyBinary()` (`:775`) | `tmp/package/bin/opencode --version` |
| Cài | `rename` + `.native-ready` | `mv tmp/package/bin/opencode ~/.opencode/installed/opencode-v<ver>` |
| Symlink | `Os.symlink()` (`:915`) | `ln -sf ~/.opencode/installed/opencode-v<ver> ~/.opencode/bin/opencode` |
| Launcher | `writeLaunchers()` (`:910`) | ghi `$PREFIX/bin/opencode` + `$PREFIX/bin/oc-install`, `chmod 755` |

---

## 13. Xử lý lỗi & chẩn đoán

### 13.1. Ma trận lỗi → nguyên nhân → xử lý

| Triệu chứng | Nguyên nhân | Xử lý |
| --- | --- | --- |
| `opencode: not installed yet.` (exit 127) | Chưa có runtime | Mở app hoặc chạy `oc-install`. |
| `opencode: the glibc runtime is missing.` (exit 127) | `glibc-runner` bị gỡ | `oc-install` (installer sẽ cài lại `glibc-runner`). |
| `opencode: this build is not patched yet; running it through the glibc loader` | `.patched` lệch & `oc-patch` hỏng/thiếu | `oc-install` để app vá lại; tạm thời vẫn chạy được qua loader. |
| `Segmentation fault` ngay khi start (exit 139) | Binary bị vá sai bằng `--set-rpath` (bản cũ) | `oc-install` (bản hiện tại chỉ sửa `PT_INTERP`). |
| `invalid ELF header` / crash ngay | `LD_PRELOAD` (bionic) còn trong env | Luôn chạy qua `$PREFIX/bin/opencode`; trong Java đã `unset`. |
| `cannot locate symbol __hash_memory` | Bản cũ dùng `patchelf` build với libc++ mới hơn `libc++` trong `$PREFIX/lib` | Cập nhật app — patcher native không phụ thuộc `libc++`. |
| `dpkg was interrupted` | Install bị kill giữa chừng | `dpkg --configure -a && apt-get -f install -y` (installer tự làm). |
| `None of the mirrors are accessible` | `pkg` dò mirror thất bại | Dùng `apt-get` trực tiếp (installer không gọi `pkg`). |
| `Failed to determine the OpenCode version` | API + npm registry không trả lời | Kiểm tra mạng/VPN, thử lại. |
| `Downloading OpenCode …` đứng im | Mạng chậm/rất lớn (~90–200 MB) | Đợi; nếu app bị kill, mở lại ⇒ tự cài tiếp phần thiếu. |
| TUI không mở nhưng `--version` OK | Lỗi hiển thị, không phải lỗi cài | Xem `~/.opencode/setup.log` + notification. |

### 13.2. File cần đọc khi có sự cố

| File | Nội dung |
| --- | --- |
| `~/.opencode/setup.log` | Log đầy đủ (append) của mọi lần chạy: lệnh, output apt, tiến trình tải, exit code. |
| `~/.opencode/last-error.txt` | Báo cáo lỗi cuối cùng: message + các bước gần nhất + tail log + diagnostics. |
| `~/.opencode/diagnostics.txt` | Snapshot mỗi lần chạy: app version, ABI, SDK, thiết bị, PATH/HOME/PREFIX/TMPDIR/LD_PRELOAD, `sources.list`, trạng thái second-stage, version đã cài. |
| `~/.opencode/.native-ready` | Phiên bản đang cài. |
| `~/.opencode/.patched` | Vân tay `"<size> <mtime>"` của binary đã vá. |

### 13.3. Quy trình phục hồi chuẩn

```sh
rm -f ~/.opencode/installed/.opencode-*.new     # dọn file tải dở
oc-install                                       # app cài lại nguyên tử
opencode --version                               # phải in ra số phiên bản
```

---

## 14. Bảo mật & rủi ro

| Rủi ro | Xử lý trong thiết kế |
| --- | --- |
| Cài package từ mirror không xác thực | Chỉ dùng repo chính thức của Termux (`packages-cf.termux.dev`, `packages.termux.dev`, `grimler.se`, TUNA) + khóa ký do `glibc-repo` cung cấp. Không tự thêm repo lạ. |
| Tải tarball npm không kiểm tra checksum | **Điểm yếu đã biết**: installer không verify SHA-512 của npm. Có thể bù bằng `npm view <pkg> dist.integrity` và so khớp; hiện tại chưa làm (xem §15 mục 7). |
| Chạy code tải về | Binary được **verify** bằng cách chạy `--version` trước khi cài (bắt được file hỏng/hack). |
| `LD_PRELOAD` của bionic lẫn vào tiến trình glibc | `unset` ở cả launcher lẫn môi trường lệnh con. |
| Updater của OpenCode tự thay binary | `OPENCODE_DISABLE_AUTOUPDATE=1` + `opencode upgrade` chuyển sang `oc-install`. |
| Ghi đè cấu hình người dùng | Không gọi `pkg`; mirror dự phòng dùng `sources.list` tạm trong `~/.opencode/apt` và bị xoá sau. |
| Chạy song song 2 installer | `claimInstaller()` (cờ `sInstallerRunning`) + hàng đợi `sInstallWaiters`. |
| App bị kill giữa lúc vá | Vá trên bản sao, `rename` atomic, không bao giờ vá file đang chạy. |
| Đường dẫn cứng `$PREFIX` trong launcher | Launcher dùng `${PREFIX:-<default>}` ⇒ vẫn chạy được nếu `PREFIX` được override. |

---

## 15. Giới hạn & đánh đổi đã biết

1. **ABI**: chỉ `arm64-v8a` và `x86_64`. Máy 32-bit không dùng được.
2. **Dung lượng**: tarball ~90–200 MB + bản giải nén + bản staging (tạm) ⇒ cần **~1 GB trống**.
3. **Bản quyền**: `appId`/`applicationId` vẫn là `com.termux` ⇒ **không** cài song song với Termux
   chính thức (và không lên được Termux chính thức vì khác khóa ký). Phải gỡ Termux chính thức trước.
4. **`versionName` phải < 0.119.0**: `login` trong `termux-tools` chỉ export
   `TERMUX_MAIN_PACKAGE_FORMAT` cho app < 0.119.0. App này export `TERMUX_APP__PACKAGE_MANAGER`
   (hai gạch dưới) nên `versionName` giữ ở `0.118.x`.
5. **Wake lock**: app giữ partial + Wi-Fi wake lock suốt vòng đời service ⇒ tăng hao pin (có thể tắt
   qua toggle trong notification).
6. **Một session duy nhất**: các nút tạo session mới bị ghim lại session cũ; mở session thứ hai
   không có chủ ý.
7. **Không có checksum tarball** (xem §14).
8. **Phụ thuộc mạng ở mỗi lần cài**: mirror bị chặn ⇒ cài thất bại (đã có 4 mirror dự phòng).

---

## 16. Tái lập thủ công (không dùng app)

Trên một bản Termux bình thường, phần runtime có thể dựng tay bằng shell — **cùng một kỹ thuật**,
chỉ khác ở chỗ "trình điều khiển" là shell thay vì Java:

```sh
set -e
PREFIX=/data/data/com.termux/files/usr

# 1. Thư viện cần thiết
apt-get update -y
apt-get install -y glibc-repo
apt-get update -y
apt-get install -y glibc-runner tar

# Patcher native: build từ app/src/main/cpp/elfpatch.c + ocpatch_main.c thành `oc-patch`
# (trong app, bản CLI này được cài sẵn ở $PREFIX/bin/oc-patch).

# 2. Phiên bản
VER=$(curl -fsSL https://opencode.ai/update/api/latest/cli/npm | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "opencode $VER"

# 3. Tải + giải nén
mkdir -p ~/.opencode/tmp && cd ~/.opencode/tmp
curl -fL -o oc.tgz "https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-$VER.tgz"
tar -xzf oc.tgz package/bin/opencode

# 4. Vá (CHỈ interpreter)
LOADER=$(ls $PREFIX/glibc/lib/ld-linux*.so.* | head -1)
oc-patch set-interpreter "$LOADER" package/bin/opencode
chmod 755 package/bin/opencode
./package/bin/opencode --version          # phải in ra số phiên bản

# 5. Cài
mkdir -p ~/.opencode/installed ~/.opencode/bin
mv package/bin/opencode ~/.opencode/installed/opencode-v$VER
ln -sf ~/.opencode/installed/opencode-v$VER ~/.opencode/bin/opencode
echo "$VER" > ~/.opencode/.native-ready
stat -L -c '%s %Y' ~/.opencode/bin/opencode > ~/.opencode/.patched
```

Lưu ý: bản shell **không** có cơ chế tự sửa của bản Java; nếu binary bị thay (vd. `opencode
upgrade` tự tải bản mới) thì marker `.patched` lệch ⇒ phải vá lại (hoặc dùng launcher do app quản lý
để tự vá).

---

## 17. Biến thể "headless": chạy đúng pipeline này bằng Java ngoài app

Tài liệu này mô tả pipeline như **một kỹ thuật**, không chỉ như một tính năng của app. Nếu muốn
dùng lại trong một công cụ khác (script CI, máy chủ, container), chỉ cần **tách lớp** như sau:

```
OpenCodeBootstrap
├── phần thuần (không phụ thuộc Android):  apt wrapper, HTTP, tar, elfpatch, launcher
└── phần phụ thuộc Android:  Activity, ProgressDialog, NotificationManager, Os.symlink/chmod
```

Bảng ánh xạ khi chạy headless (ví dụ trong Termux bằng `javac`/`java`, hoặc trong container Debian):

| API Android | Thay bằng headless | Ghi chú |
| --- | --- | --- |
| `android.system.Os.symlink` | `ln -sfn <target> <link>` qua `ProcessBuilder` | Hoặc `Files.createSymbolicLink` nếu FS hỗ trợ. |
| `android.system.Os.chmod` | `File.setExecutable(true, false)` hoặc `chmod 755` | Java dùng `PosixFilePermission` nếu chạy trên FS POSIX. |
| `NotificationManager` / `ProgressDialog` | `System.out` + `java.util.logging` | Giữ nguyên câu chữ `status()` để log đọc được. |
| `TermuxShellEnvironment.getEnvironment()` | `System.getenv()` + bổ sung `PREFIX`/`HOME` | Bản headless chạy trên Linux thật không cần `LD_PRELOAD` bionic ⇒ bỏ nhánh `unset`. |
| `Logger` của Termux | `java.util.logging.Logger` hoặc `System.err` | Giữ định dạng `== step` / `$ cmd` / `[exit N]`. |
| `Build.SUPPORTED_ABIS` | `os.arch` → `aarch64`→arm64, `amd64`→x64 | Cùng bảng ánh xạ. |

Nguyên tắc giữ nguyên khi tách:

1. **Gate** trước khi đụng `$PREFIX` (bootstrap phải sẵn sàng).
2. **Không chạm cấu hình người dùng** (mirror dự phòng qua `sources.list` tạm).
3. **Verify trước khi cài** (`--version` phải in ra số phiên bản).
4. **Vá trên bản sao, cài bằng rename atomic**.
5. **Marker `.patched` chỉ ghi sau khi xác nhận `PT_INTERP` đúng** (`ElfPatch.getInterpreter`).
6. **Ghi launcher có `unset LD_PRELOAD`** và fallback qua loader.
7. **Idempotent**: chạy lại không tải lại, không phá trạng thái tốt.

---

## 18. Phụ lục

### 18.1. Danh mục URL

| Mục đích | URL |
| --- | --- |
| Phiên bản (API) | `https://opencode.ai/update/api/latest/cli/npm` |
| Phiên bản (npm) | `https://registry.npmjs.org/@opencode/cli-linux-<arch>/latest` |
| Tarball | `https://registry.npmjs.org/@opencode/cli-linux-<arch>/-/<file>.tgz` |
| Tarball (fallback scope) | `https://registry.npmjs.org/@opencode-ai/cli-linux-<arch>/-/<file>.tgz` |
| Repo chính | `https://packages-cf.termux.dev/apt/termux-main/`, `https://packages.termux.dev/apt/termux-main/`, `https://grimler.se/termux-main/`, `https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/` |
| Repo glibc | `https://packages-cf.termux.dev/apt/termux-glibc/`, `https://packages.termux.dev/apt/termux-glibc/` |
| Mã nguồn repo glibc | `https://github.com/termux/glibc-packages` (có `repo.json`) |

### 18.2. Danh mục lệnh ngoài được gọi

| Lệnh | Vị trí | Vai trò |
| --- | --- | --- |
| `apt-get` | `$PREFIX/bin/apt-get` | Cài `glibc-repo`, `glibc-runner`, `tar`, `termux-am`, `ripgrep`; `dpkg --configure -a` khi cần sửa DB. |
| `dpkg` | `$PREFIX/bin/dpkg` | Sửa database package bị giết dở. |
| `tar` | `$PREFIX/bin/tar` | Giải nén `package/bin/opencode`. |
| `oc-patch` | `$PREFIX/bin/oc-patch` (từ `libocpatch-exec.so` trong APK) | Vá `PT_INTERP`, đọc `PT_INTERP`. Không phải gói apt. |
| `bash` | `$PREFIX/bin/bash` | Chạy second-stage của bootstrap Termux. |
| `am` | `$PREFIX/bin/am` | `oc-install` mở app bằng intent `FORCE_UPDATE`. |

### 18.3. Checklist QA

- [ ] `opencode --version` in ra số phiên bản **từ launcher** (`$PREFIX/bin/opencode`), không phải từ `~/.opencode/bin/opencode`.
- [ ] `oc-patch get-interpreter ~/.opencode/installed/opencode-v<ver>` trỏ đúng `$PREFIX/glibc/lib/ld-linux-*.so.*`.
- [ ] `~/.opencode/.patched` khớp `stat -L -c '%s %Y' ~/.opencode/bin/opencode`.
- [ ] `~/.opencode/.native-ready` chứa đúng version đang chạy.
- [ ] `~/.opencode/installed/` không còn file `.opencode-*.new` (tạm).
- [ ] `$HOME/.opencode/setup.sh` **không** tồn tại (đã gỡ installer script cũ).
- [ ] `~/.opencode/apt/` không còn sót (`sources.list` tạm đã dọn).
- [ ] Kill app giữa lúc tải ⇒ mở lại ⇒ installer tiếp tục, không tải lại từ đầu nếu binary đã verify.
- [ ] Xoá `$PREFIX/bin/oc-patch` ⇒ mở app ⇒ app cài lại nó từ APK (không cần mạng).
- [ ] `opencode upgrade` ⇒ đi qua `oc-install` ⇒ app vá lại binary mới (marker cập nhật).
- [ ] `env | grep LD_PRELOAD` trong session Termux vẫn có ⇒ nhưng tiến trình `opencode` **không** được preload ⇒ vẫn chạy.
- [ ] `~/.opencode/diagnostics.txt` có `abi:`, `sdk:`, `sources.list` ⇒ dùng khi gửi issue.

### 18.4. Tóm tắt một câu

> Tải thư viện bằng `apt-get` (glibc-runner + tar) → tải tarball OpenCode từ npm →
> **chỉ vá `PT_INTERP`** bằng patcher native (`oc-patch`/`elfpatch.c`) trỏ tới loader glibc của Termux →
> verify bằng `--version` → cài bằng `rename` atomic → khởi chạy qua launcher có `unset LD_PRELOAD`
> và cơ chế tự vá lại theo marker `.patched` — tất cả trong **một class Java duy nhất**
> (`OpenCodeBootstrap.java`), không root, không proot, không chroot.
