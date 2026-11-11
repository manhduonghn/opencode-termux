package com.termux.app;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Filesystem helpers shared by the file manager, the text editor and the properties dialog.
 *
 * <p>Everything here works with {@link java.io.File} plus the Android/Linux system calls that
 * {@code java.io} does not expose (symbolic links, POSIX permissions, SELinux context). Symlinks
 * are never followed by recursive operations.</p>
 */
final class FileManagerUtils {

    /** Returned when a size or permission value could not be read. */
    static final int UNKNOWN = -1;

    /** The largest file the built-in text editor will open. */
    static final long TEXT_LIMIT = 4L * 1024L * 1024L;

    /** Stop counting after this many entries so that properties stay responsive. */
    static final int MEASURE_LIMIT = 200_000;

    /** Refuse archives with more entries than this. */
    private static final int ZIP_ENTRY_LIMIT = 100_000;

    private static final String SELINUX_XATTR = "security.selinux";

    private static final String[] TEXT_EXTENSIONS = {
        ".txt", ".md", ".markdown", ".json", ".jsonc", ".xml", ".html", ".htm", ".css",
        ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".java", ".kt", ".kts", ".gradle",
        ".groovy", ".c", ".h", ".cc", ".cpp", ".hpp", ".py", ".rb", ".php", ".sh", ".bash",
        ".zsh", ".fish", ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".properties",
        ".csv", ".tsv", ".sql", ".log", ".gitignore", ".gitattributes", ".editorconfig",
        ".env", ".service", ".desktop", ".srt", ".vtt", ".tex", ".diff", ".patch", ".lock",
        ".pro", ".mk", ".cmake", ".dockerfile", ".makefile"
    };

    private static final String[] TEXT_MIME_TYPES = {
        "application/json", "application/xml", "application/xhtml+xml",
        "application/javascript", "application/x-javascript", "application/ecmascript",
        "application/x-sh", "application/x-shellscript", "application/sql",
        "application/yaml", "application/x-yaml", "application/toml",
        "application/x-httpd-php", "application/rtf"
    };

    /** Receives progress while a recursive operation runs. Return false to cancel. */
    interface Progress {
        boolean onProgress(String name);
    }

    /** Result of a recursive size/count scan. */
    static final class SizeInfo {
        final long size;
        final int files;
        final int directories;
        final boolean truncated;

        SizeInfo(long size, int files, int directories, boolean truncated) {
            this.size = size;
            this.files = files;
            this.directories = directories;
            this.truncated = truncated;
        }
    }

    /** MD5, SHA-1 and SHA-256 of a file. */
    static final class Checksums {
        final String md5;
        final String sha1;
        final String sha256;

        Checksums(String md5, String sha1, String sha256) {
            this.md5 = md5;
            this.sha1 = sha1;
            this.sha256 = sha256;
        }
    }

    private FileManagerUtils() {
    }

    /* ==========================================================================================
     * Paths and names
     * ========================================================================================== */

    static boolean isSymbolicLink(File file) {
        if (file == null) return false;
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return OsConstants.S_ISLNK(stat.st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    static File canonical(File file) {
        if (file == null) return new File("/");
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file.getAbsoluteFile();
        }
    }

    static boolean samePath(File left, File right) {
        if (left == null || right == null) return false;
        return canonical(left).getAbsolutePath().equals(canonical(right).getAbsolutePath());
    }

    static boolean isSameOrInside(File candidate, File ancestor) {
        if (candidate == null || ancestor == null) return false;
        String candidatePath = canonical(candidate).getAbsolutePath();
        String ancestorPath = canonical(ancestor).getAbsolutePath();
        return candidatePath.equals(ancestorPath)
            || candidatePath.startsWith(ancestorPath + File.separator);
    }

    /** Make a name safe for use as a single file name (not a path). */
    static String safeFileName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return safe.isEmpty() ? "files" : safe;
    }

    /** A non-existing target in {@code directory}, adding " (1)", " (2)", … when needed. */
    static File uniqueTarget(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists() && !isSymbolicLink(file)) return file;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            File candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists() && !isSymbolicLink(candidate)) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    /* ==========================================================================================
     * Copy, delete and measure
     * ========================================================================================== */

    static void copyRecursively(File source, File target) throws IOException {
        if (isSymbolicLink(source)) {
            throw new IOException("Symlinks are not copied");
        }
        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("Cannot create " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (isSymbolicLink(child)) continue;
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        if (!source.isFile()) throw new IOException("Not a file");

        try (BufferedInputStream input =
                 new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output =
                 new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Cancelled");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    static boolean deleteRecursively(File file) {
        if (isSymbolicLink(file)) return file.delete();
        if (file.isDirectory()) {
            // Git object files can be read-only, and a read-only directory would block the
            // deletion of its children. Make the directory writable before descending into it.
            file.setWritable(true);
            file.setReadable(true);
            file.setExecutable(true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        } else {
            file.setWritable(true);
        }
        return file.delete();
    }

    /** Recursively sum the size and count the files of a tree, without following symlinks. */
    static SizeInfo measure(File root, int limit) {
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(root);
        long total = 0;
        int files = 0;
        int directories = 0;
        boolean truncated = false;
        while (!stack.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                truncated = true;
                break;
            }
            File file = stack.pop();
            if (isSymbolicLink(file)) continue;
            if (file.isFile()) {
                total += Math.max(0, file.length());
                files++;
            } else if (file.isDirectory()) {
                directories++;
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) stack.push(child);
                }
            }
            if (files + directories > limit) {
                truncated = true;
                break;
            }
        }
        return new SizeInfo(total, files, directories, truncated);
    }

    /* ==========================================================================================
     * MIME type and text files
     * ========================================================================================== */

    static String mimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /** Whether the file looks like text and is small enough for the built-in editor. */
    static boolean isTextFile(File file) {
        if (file == null || !file.isFile() || file.length() > TEXT_LIMIT) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String extension : TEXT_EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        String mime = mimeType(file);
        if (mime.startsWith("text/")) return true;
        for (String textMime : TEXT_MIME_TYPES) {
            if (mime.equals(textMime)) return true;
        }

        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read = input.read(buffer);
            if (read <= 0) return true;
            int control = 0;
            for (int i = 0; i < read; i++) {
                int value = buffer[i] & 0xFF;
                if (value == 0) return false;
                if (value < 0x09 || (value > 0x0D && value < 0x20)) control++;
            }
            return control * 100 < read;
        } catch (IOException e) {
            return false;
        }
    }

    /** Read a small text file as UTF-8, stripping a UTF-8 BOM when present. */
    static String readText(File file) throws IOException {
        long length = file.length();
        if (length > TEXT_LIMIT) throw new IOException("File is too large");
        if (length <= 0) return "";
        byte[] bytes = new byte[(int) length];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset < bytes.length) {
                byte[] shorter = new byte[offset];
                System.arraycopy(bytes, 0, shorter, 0, offset);
                bytes = shorter;
            }
        }
        int offset = 0;
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    /**
     * Atomically write UTF-8 text: the content is written to a temporary file in the same
     * directory and then renamed over the target. The original POSIX mode is preserved.
     */
    static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent == null) throw new IOException("No parent directory");
        int mode = modeOf(file);
        File temporary = new File(parent, "." + file.getName() + ".tmp-" + System.nanoTime());
        boolean written = false;
        try {
            try (OutputStream output =
                     new BufferedOutputStream(new FileOutputStream(temporary))) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            written = true;
            if (!temporary.renameTo(file)) {
                copyRecursively(temporary, file);
                if (!temporary.delete()) temporary.deleteOnExit();
            } else if (mode >= 0) {
                try {
                    Os.chmod(file.getAbsolutePath(), mode & 07777);
                } catch (Exception ignored) {
                    // Preserving the mode is best effort, e.g. on FUSE filesystems.
                }
            }
        } finally {
            if (!written || temporary.exists()) temporary.delete();
        }
    }

    /* ==========================================================================================
     * ZIP archives
     * ========================================================================================== */

    static void createZip(List<File> sources, File destination, Progress progress)
        throws IOException {
        if (sources == null || sources.isEmpty()) throw new IOException("Nothing to compress");
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream output = new ZipOutputStream(
            new BufferedOutputStream(new FileOutputStream(destination)))) {
            for (File source : sources) {
                if (source == null) continue;
                String entryName = uniqueEntryName(source.getName(), usedNames);
                addToZip(source, entryName, destination, output, progress);
            }
        }
    }

    private static String uniqueEntryName(String name, Set<String> used) {
        String safe = safeFileName(name);
        String candidate = safe;
        int index = 1;
        while (used.contains(candidate)) {
            candidate = safe + " (" + index++ + ")";
        }
        used.add(candidate);
        return candidate;
    }

    private static void addToZip(File file, String entryName, File destination,
                                 ZipOutputStream output, Progress progress) throws IOException {
        if (isSymbolicLink(file)) return;
        if (samePath(file, destination)) return;
        if (progress != null && !progress.onProgress(entryName)) {
            throw new IOException("Cancelled");
        }
        if (file.isDirectory()) {
            ZipEntry directory = new ZipEntry(entryName + "/");
            if (file.lastModified() > 0) directory.setTime(file.lastModified());
            output.putNextEntry(directory);
            output.closeEntry();
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) {
                addToZip(child, entryName + "/" + child.getName(), destination, output, progress);
            }
            return;
        }
        if (!file.isFile()) return;

        ZipEntry entry = new ZipEntry(entryName.replace('\\', '/'));
        if (file.lastModified() > 0) entry.setTime(file.lastModified());
        output.putNextEntry(entry);
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Cancelled");
                }
                output.write(buffer, 0, read);
            }
        }
        output.closeEntry();
    }

    /**
     * Extract a ZIP archive into {@code destination}. Entry names are validated so an archive
     * cannot write outside the destination (zip-slip).
     *
     * @return the number of extracted files.
     */
    static int extractZip(File archive, File destination, Progress progress) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create the destination directory");
        }
        String destinationPath = canonical(destination).getAbsolutePath();
        int extracted = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                ZipEntry entry = enumeration.nextElement();
                if (++entries > ZIP_ENTRY_LIMIT) {
                    throw new IOException("Archive has too many entries");
                }
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;
                if (progress != null && !progress.onProgress(name)) {
                    throw new IOException("Cancelled");
                }

                name = name.replace('\\', '/');
                while (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/") || name.equals(".") || name.equals("..")
                    || name.startsWith("../") || name.contains("/../")
                    || name.endsWith("/..") || name.endsWith("/.")
                    || name.matches("^[A-Za-z]:.*") || name.indexOf('\0') >= 0) {
                    throw new IOException("Unsafe archive entry: " + name);
                }

                File target = new File(destination, name);
                String targetPath = canonical(target).getAbsolutePath();
                if (!targetPath.equals(destinationPath)
                    && !targetPath.startsWith(destinationPath + File.separator)) {
                    throw new IOException("Unsafe archive entry: " + name);
                }
                if (targetPath.equals(destinationPath)
                    && !entry.isDirectory() && !name.endsWith("/")) {
                    throw new IOException("Unsafe archive entry: " + name);
                }

                if (entry.isDirectory() || name.endsWith("/")) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("Cannot create " + target.getAbsolutePath());
                    }
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent.getAbsolutePath());
                }
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(target))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Cancelled");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (entry.getTime() > 0) target.setLastModified(entry.getTime());
                extracted++;
            }
        }
        return extracted;
    }

    /** Whether a file name looks like a ZIP archive that can be extracted. */
    static boolean isZipArchive(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".apk")
            || name.endsWith(".xpi") || name.endsWith(".epub") || name.endsWith(".aar");
    }

    /* ==========================================================================================
     * Properties: checksums, permissions, symlinks and SELinux
     * ========================================================================================== */

    static Checksums checksums(File file) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Cancelled");
                }
                md5.update(buffer, 0, read);
                sha1.update(buffer, 0, read);
                sha256.update(buffer, 0, read);
            }
        }
        return new Checksums(hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()));
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0F];
        }
        return new String(result);
    }

    static StructStat stat(File file) {
        if (file == null) return null;
        try {
            return Os.lstat(file.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /** The full POSIX mode, including setuid/setgid/sticky bits, or {@link #UNKNOWN}. */
    static int modeOf(File file) {
        StructStat stat = stat(file);
        return stat == null ? UNKNOWN : (stat.st_mode & 07777);
    }

    static String modeString(File file) {
        StructStat stat = stat(file);
        return stat == null ? null : modeToString(stat.st_mode);
    }

    static String modeToString(int mode) {
        char[] result = new char[10];
        if (OsConstants.S_ISDIR(mode)) result[0] = 'd';
        else if (OsConstants.S_ISLNK(mode)) result[0] = 'l';
        else if (OsConstants.S_ISCHR(mode)) result[0] = 'c';
        else if (OsConstants.S_ISBLK(mode)) result[0] = 'b';
        else if (OsConstants.S_ISFIFO(mode)) result[0] = 'p';
        else if (OsConstants.S_ISSOCK(mode)) result[0] = 's';
        else if (OsConstants.S_ISREG(mode)) result[0] = '-';
        else result[0] = '?';

        result[1] = (mode & 0400) != 0 ? 'r' : '-';
        result[2] = (mode & 0200) != 0 ? 'w' : '-';
        result[3] = (mode & 0100) != 0
            ? ((mode & OsConstants.S_ISUID) != 0 ? 's' : 'x')
            : ((mode & OsConstants.S_ISUID) != 0 ? 'S' : '-');
        result[4] = (mode & 0040) != 0 ? 'r' : '-';
        result[5] = (mode & 0020) != 0 ? 'w' : '-';
        result[6] = (mode & 0010) != 0
            ? ((mode & OsConstants.S_ISGID) != 0 ? 's' : 'x')
            : ((mode & OsConstants.S_ISGID) != 0 ? 'S' : '-');
        result[7] = (mode & 0004) != 0 ? 'r' : '-';
        result[8] = (mode & 0002) != 0 ? 'w' : '-';
        result[9] = (mode & 0001) != 0
            ? ((mode & OsConstants.S_ISVTX) != 0 ? 't' : 'x')
            : ((mode & OsConstants.S_ISVTX) != 0 ? 'T' : '-');
        return new String(result);
    }

    static String octal(int mode) {
        if (mode < 0) return "?";
        return Integer.toOctalString(mode & 07777);
    }

    static int parseOctalMode(String text) {
        if (text == null) return UNKNOWN;
        String value = text.trim();
        if (value.startsWith("0o") || value.startsWith("0O")) {
            value = value.substring(2);
        }
        if (value.isEmpty() || value.length() > 4) return UNKNOWN;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '7') return UNKNOWN;
        }
        try {
            int mode = Integer.parseInt(value, 8);
            return mode >= 0 && mode <= 07777 ? mode : UNKNOWN;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    static void setMode(File file, int mode) throws Exception {
        if (file == null || mode < 0 || mode > 07777) {
            throw new IllegalArgumentException("Invalid mode");
        }
        Os.chmod(file.getAbsolutePath(), mode);
    }

    static String symlinkTarget(File file) {
        if (file == null) return null;
        try {
            return Os.readlink(file.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    static String selinuxContext(File file) {
        // Os.getxattr was added in API 26; older releases simply have no SELinux row.
        if (file == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            byte[] value = Os.getxattr(file.getAbsolutePath(), SELINUX_XATTR);
            if (value == null || value.length == 0) return null;
            int length = value.length;
            if (value[length - 1] == 0) length--;
            return new String(value, 0, length, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static String ownerGroup(File file) {
        StructStat stat = stat(file);
        if (stat == null) return null;
        return stat.st_uid + ":" + stat.st_gid;
    }

    static String formatDate(long timestamp) {
        if (timestamp <= 0) return null;
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date(timestamp));
    }

    /* ==========================================================================================
     * Small helpers used by the dialogs
     * ========================================================================================== */

    static List<File> asList(File... files) {
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file != null) result.add(file);
            }
        }
        return result;
    }
}
