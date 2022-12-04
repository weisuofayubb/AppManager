// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.util.zip.ZipEntry;

import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.io.FileSystemManager;
import io.github.muntashirakon.io.IoUtils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

public final class FileUtils {
    public static final String TAG = FileUtils.class.getSimpleName();

    @AnyThread
    public static boolean isZip(@NonNull Path path) throws IOException {
        int header;
        try (InputStream is = path.openInputStream()) {
            byte[] headerBytes = new byte[4];
            is.read(headerBytes);
            header = new BigInteger(headerBytes).intValue();
        }
        return header == 0x504B0304 || header == 0x504B0506 || header == 0x504B0708;
    }

    @AnyThread
    public static boolean isZip(@NonNull InputStream is) throws IOException {
        if (!is.markSupported()) throw new IOException("InputStream must support mark.");
        int header;
        byte[] headerBytes = new byte[4];
        is.mark(4);
        is.read(headerBytes);
        is.reset();
        header = new BigInteger(headerBytes).intValue();
        return header == 0x504B0304 || header == 0x504B0506 || header == 0x504B0708;
    }

    @WorkerThread
    @NonNull
    public static Path saveZipFile(@NonNull InputStream zipInputStream,
                                   @NonNull Path destinationDirectory,
                                   @NonNull String fileName)
            throws IOException {
        return saveZipFile(zipInputStream, destinationDirectory.findOrCreateFile(fileName, null));
    }

    @WorkerThread
    @NonNull
    public static Path saveZipFile(@NonNull InputStream zipInputStream, @NonNull Path filePath) throws IOException {
        try (OutputStream outputStream = filePath.openOutputStream()) {
            IoUtils.copy(zipInputStream, outputStream);
        }
        return filePath;
    }

    @AnyThread
    @NonNull
    public static String getFileNameFromZipEntry(@NonNull ZipEntry zipEntry) {
        return Paths.getLastPathSegment(zipEntry.getName());
    }

    @AnyThread
    @Nullable
    public static String getSanitizedFileName(@NonNull String fileName, boolean replaceSpace) {
        if (fileName.equals(".") || fileName.equals("..")) {
            return null;
        }
        fileName = fileName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (replaceSpace) {
            fileName = fileName.replaceAll("\\s", "_");
        }
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        return fileName;
    }

    @AnyThread
    @NonNull
    public static File getFileFromFd(@NonNull ParcelFileDescriptor fd) {
        return new File("/proc/self/fd/" + fd.getFd());
    }

    @AnyThread
    public static void deleteSilently(@Nullable Path path) {
        if (path == null || !path.exists()) return;
        if (!path.delete()) {
            Log.w(TAG, String.format("Unable to delete %s", path));
        }
    }

    @AnyThread
    public static void deleteSilently(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (!file.delete()) {
            Log.w(TAG, String.format("Unable to delete %s", file.getAbsoluteFile()));
        }
    }

    @WorkerThread
    @NonNull
    public static String getContentFromAssets(@NonNull Context context, String fileName) {
        try (InputStream inputStream = context.getResources().getAssets().open(fileName)) {
            return IoUtils.getInputStreamContent(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @AnyThread
    public static boolean isAssetDirectory(@NonNull Context context, @NonNull String path) {
        String[] files;
        try {
            files = context.getAssets().list(path);
        } catch (IOException e) {
            // Doesn't exist
            return false;
        }
        return files != null && files.length > 0;
    }

    @WorkerThread
    public static void copyFromAsset(@NonNull Context context, String fileName, File destFile) {
        try (AssetFileDescriptor openFd = context.getAssets().openFd(fileName)) {
            try (InputStream open = openFd.createInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                IoUtils.copy(open, fos);
                fos.flush();
                fos.getFD().sync();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @WorkerThread
    @NonNull
    public static File getCachedFile(InputStream inputStream, @Nullable String extension) throws IOException {
        File tempFile = getTempFile(extension);
        try (OutputStream outputStream = new FileOutputStream(tempFile)) {
            IoUtils.copy(inputStream, outputStream);
        }
        return tempFile;
    }

    @AnyThread
    @NonNull
    public static File getTempFile(@Nullable String extension) throws IOException {
        return File.createTempFile("file_", "." + extension, getCachePath());
    }

    @AnyThread
    @NonNull
    public static Path getTempPath(String relativeDir, String filename) {
        File newDir = new File(getCachePath() + File.separator + relativeDir);
        int i = 1;
        while (newDir.exists()) {
            newDir = new File(getCachePath() + File.separator + (relativeDir + "_" + i));
        }
        newDir.mkdirs();
        return Paths.get(new File(newDir, filename));
    }

    @AnyThread
    @NonNull
    public static File getCachePath() {
        Context context = AppManager.getContext();
        try {
            return getExternalCachePath(context);
        } catch (IOException e) {
            return context.getCacheDir();
        }
    }

    @AnyThread
    @NonNull
    public static File getExternalCachePath(@NonNull Context context) throws IOException {
        File extDir = context.getExternalCacheDir();
        if (extDir == null) {
            throw new FileNotFoundException("External storage unavailable.");
        }
        if (!extDir.exists() && !extDir.mkdirs()) {
            throw new IOException("Cannot create cache directory in the external storage.");
        }
        String storageState = Environment.getExternalStorageState(extDir);
        if (storageState != null && !Environment.MEDIA_MOUNTED.equals(storageState)) {
            throw new FileNotFoundException("External media not present");
        }
        return extDir;
    }

    @AnyThread
    public static void chmod644(@NonNull File file) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), 420);
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to apply mode 644 to " + file);
            throw new IOException(e);
        }
    }

    public static boolean canRead(@NonNull File file) {
        if (file.canRead()) {
            try (FileChannel ignored = FileSystemManager.getLocal().openChannel(file, FileSystemManager.MODE_READ_ONLY)) {
                return true;
            } catch (IOException | SecurityException e) {
                return false;
            }
        }
        return false;
    }
}
