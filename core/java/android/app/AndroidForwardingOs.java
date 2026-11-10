/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import static android.content.ContentResolver.DEPRECATE_DATA_COLUMNS;
import static android.content.ContentResolver.DEPRECATE_DATA_PREFIX;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.FileUtils;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import libcore.io.ForwardingOs;
import libcore.io.IoUtils;
import libcore.io.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;


/**
 * Installs selective syscall interception.
 *
 * <p>For example, this is used to implement special filesystem paths that will be redirected to
 * {@link ContentResolver#openFileDescriptor(Uri, String)}.
 *
 * @hide
 */
class AndroidForwardingOs extends ForwardingOs {
    private static final String TAG = "AndroidForwardingOs";

    public static void install() {
        // If feature is disabled, we don't need to install
        if (!DEPRECATE_DATA_COLUMNS) return;

        // Install interception and make sure it sticks!
        Os def;
        do {
            def = Os.getDefault();
        } while (!Os.compareAndSetDefault(def, new AndroidForwardingOs(def)));
    }

    private AndroidForwardingOs(Os os) {
        super(os);
    }

    private FileDescriptor openDeprecatedDataPath(String path, int mode) throws ErrnoException {
        final Uri uri = ContentResolver.translateDeprecatedDataPath(path);
        Log.v(TAG, "Redirecting " + path + " to " + uri);

        final ContentResolver cr = ActivityThread.currentActivityThread().getApplication()
                .getContentResolver();
        try {
            final FileDescriptor fd = new FileDescriptor();
            fd.setInt$(cr.openFileDescriptor(uri,
                    FileUtils.translateModePosixToString(mode)).detachFd());
            return fd;
        } catch (SecurityException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.EACCES);
        } catch (FileNotFoundException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.ENOENT);
        }
    }

    private void deleteDeprecatedDataPath(String path) throws ErrnoException {
        final Uri uri = ContentResolver.translateDeprecatedDataPath(path);
        Log.v(TAG, "Redirecting " + path + " to " + uri);

        final ContentResolver cr = ActivityThread.currentActivityThread().getApplication()
                .getContentResolver();
        try {
            if (cr.delete(uri, null, null) == 0) {
                throw new FileNotFoundException();
            }
        } catch (SecurityException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.EACCES);
        } catch (FileNotFoundException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.ENOENT);
        }
    }

    @Override
    public boolean access(String path, int mode) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            // If we opened it okay, then access check succeeded
            IoUtils.closeQuietly(
                    openDeprecatedDataPath(path, FileUtils.translateModeAccessToPosix(mode)));
            return true;
        } else {
            return super.access(path, mode);
        }
    }

    @Override
    public FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            return openDeprecatedDataPath(path, mode);
        } else {
            return super.open(path, flags, mode);
        }
    }

    @Override
    public StructStat stat(String path) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            final FileDescriptor fd = openDeprecatedDataPath(path, OsConstants.O_RDONLY);
            try {
                return android.system.Os.fstat(fd);
            } finally {
                IoUtils.closeQuietly(fd);
            }
        } else {
            return super.stat(path);
        }
    }

    @Override
    public void unlink(String path) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            deleteDeprecatedDataPath(path);
        } else {
            super.unlink(path);
        }
    }

    @Override
    public void remove(String path) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            deleteDeprecatedDataPath(path);
        } else {
            super.remove(path);
        }
    }

    @Override
    public void rename(String oldPath, String newPath) throws ErrnoException {
        try {
            super.rename(oldPath, newPath);
        } catch (ErrnoException e) {
            // On emulated volumes, we have bind mounts for /Android/data and
            // /Android/obb, which prevents move from working across those directories
            // and other directories on the filesystem. Per-directory pass-through
            // mounts (e.g. fde_ptfs) additionally make even moving a plain folder to
            // another directory (such as .trash-storage) fail with EXDEV. To work
            // around that, try to recover by doing a copy instead.
            // Note that we only do this for "/storage/emulated", because public volumes
            // don't have these bind mounts, neither do private volumes that are not
            // the primary storage.
            if (e.errno == OsConstants.EXDEV && oldPath.startsWith("/storage/emulated")
                    && newPath.startsWith("/storage/emulated")) {
                Log.v(TAG, "Recovering failed rename " + oldPath + " to " + newPath);
                try {
                    final File oldFile = new File(oldPath);
                    final File newFile = new File(newPath);
                    if (oldFile.isDirectory()) {
                        // Files.move() cannot copy a non-empty directory across file
                        // systems (it fails with DirectoryNotEmptyException), so move
                        // the whole directory tree with a recursive copy + delete.
                        moveDirectoryAcrossFileSystems(oldFile, newFile);
                    } else {
                        Files.move(oldFile.toPath(), newFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e2) {
                    Log.e(TAG, "Rename recovery failed ", e2);
                    throw e;
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * Moves a directory (and all of its contents) to another location even when the
     * source and destination live on different file systems, by recursively copying
     * the whole tree and then deleting the source directory.
     */
    private static void moveDirectoryAcrossFileSystems(File source, File target)
            throws IOException {
        if (source.isFile()) {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        final Path sourcePath = source.toPath();
        final Path targetDir = target.toPath();
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }
        if (!Files.isDirectory(targetDir)) {
            throw new IOException("Destination is not a directory: " + target);
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(sourcePath)) {
            for (Path child : children) {
                final Path destinationChild = targetDir.resolve(child.getFileName().toString());
                if (Files.isSymbolicLink(child)) {
                    Files.createSymbolicLink(destinationChild, Files.readSymbolicLink(child));
                } else if (Files.isDirectory(child)) {
                    moveDirectoryAcrossFileSystems(child.toFile(), destinationChild.toFile());
                } else {
                    Files.copy(child, destinationChild,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // Delete the source tree once every entry has been copied over.
        Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
