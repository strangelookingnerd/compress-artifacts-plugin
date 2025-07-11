/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.compress_artifacts;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.util.io.ArchiverFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jenkins.util.VirtualFile;

import org.springframework.web.util.UriUtils;

final class ZipStorage extends VirtualFile {

    static VirtualFile root(File archive) {
        return new ZipStorage(archive, "");
    }

    // TODO support updating entries
    static void archive(File archive, FilePath workspace, Launcher launcher, BuildListener listener, Map<String,String> artifacts) throws IOException, InterruptedException {
        // Use temporary file for writing, rename when done
        File tempArchive = new File(archive.getAbsolutePath() + ".writing.zip");

        try (OutputStream os = new FileOutputStream(tempArchive)) {
            workspace.archive(ArchiverFactory.ZIP, os, new FilePath.ExplicitlySpecifiedDirScanner(artifacts));
        }

        tempArchive.renameTo(archive);
    }

    static boolean delete(File archive) throws IOException, InterruptedException {
        return archive.delete();
    }

    private final File archive;
    private final String path;

    private ZipStorage(File archive, String path) {
        this.archive = archive;
        this.path = path;
    }

    @Override @NonNull public String getName() {
        return path.replaceFirst("^(.+/)?([^/]+)/?$", "$2");
    }

    @Override @NonNull public URI toURI() {
        try {
            // If no scheme is provided, beginning of the path is parsed as the scheme causing validation problems.
            // Using some scheme to workaround that + prepending prefix to avoid empty URI path.
            return new URI("zip", "./" + UriUtils.encodePath(path, StandardCharsets.UTF_8), null);
        } catch (URISyntaxException x) {
            throw new AssertionError(x);
        }
    }

    @Override public VirtualFile getParent() {
        int length = path.length();
        if (length == 0) return null; // Root has no parent

        int last = path.lastIndexOf('/');
        if (last < 0) return root(archive); // Top level file

        if (last + 1 != length) {
            return new ZipStorage(archive, path.substring(0, last + 1));
        }

        // trailing '/' found
        last = path.lastIndexOf('/', last - 1);
        if (last == -1) return root(archive); // Top level dir

        return new ZipStorage(archive, path.substring(0, last + 1));
    }

    private boolean looksLikeDir() {
        return path.isEmpty() || path.endsWith("/");
    }

    @Override public boolean isDirectory() throws IOException {
        if (!looksLikeDir() || !archive.exists()) {
            return false;
        }
        try (ZipFile zf = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String p = entry.getName();
                if (p.startsWith(path)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override public boolean isFile() throws IOException {
        if (looksLikeDir() || !archive.exists()) {
            return false;
        }
        try (ZipFile zf = new ZipFile(archive)) {
            return zf.getEntry(path) != null;
        }
    }

    @Override public boolean exists() throws IOException {
        if (!archive.exists()) return false;

        try (ZipFile zf = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String p = entry.getName();
                if (looksLikeDir() ? p.startsWith(path) : p.equals(path)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override @NonNull public VirtualFile[] list() throws IOException {
        if (!looksLikeDir() || !archive.exists()) {
            return new VirtualFile[0];
        }
        try (ZipFile zf = new ZipFile(archive)) {
            Set<VirtualFile> files = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String p = entry.getName();
                if (p.startsWith(path)) {
                    String pth = path + p.substring(path.length()).replaceFirst("/.+", "/");
                    files.add(new ZipStorage(archive, pth));
                }
            }
            return files.toArray(new VirtualFile[0]);
        }
    }

    @Override @NonNull public VirtualFile child(@NonNull String name) {
        // TODO this is ugly; would be better to not require / on path
        ZipStorage f = new ZipStorage(archive, path + name + '/');
        try {
            if (f.isDirectory()) {
                return f;
            }
        } catch (IOException x) {
        }
        return new ZipStorage(archive, path + name);
    }

    @Override public long length() throws IOException {
        if (!archive.exists()) return 0;

        try (ZipFile zf = new ZipFile(archive)) {
            ZipEntry entry = zf.getEntry(path);
            return entry != null ? entry.getSize() : 0;
        }
    }

    @Override public long lastModified() throws IOException {
        if (!archive.exists()) return 0;

        try (ZipFile zf = new ZipFile(archive)) {
            ZipEntry entry = zf.getEntry(path);
            return entry != null ? entry.getTime() : 0;
        }
    }

    @Override public boolean canRead() throws IOException {
        return true;
    }

    @Override public InputStream open() throws IOException {
        if (!archive.exists()) throw new FileNotFoundException(path + " (No such file or directory)");

        if (looksLikeDir()) {
            // That is what java.io.FileInputStream.open throws
            throw new FileNotFoundException(this + " (Is a directory)");
        }
        final ZipFile zf = new ZipFile(archive);
        ZipEntry entry = zf.getEntry(path);
        if (entry == null) {
            zf.close();
            throw new FileNotFoundException(path + " (No such file or directory)");
        }

        return new EntryInputStream(zf, entry);
    }

    private static final class EntryInputStream extends FilterInputStream {

        private static final Logger LOGGER = Logger.getLogger(EntryInputStream.class.getName());

        private final @NonNull ZipFile archive;
        private Exception acquired = new Exception("Opened by:");

        private EntryInputStream(@NonNull ZipFile archive, ZipEntry entry) throws IOException {
            super(archive.getInputStream(entry));
            this.archive = archive;
        }

        @Override
        public void close() throws IOException {
            super.close();
            archive.close();
            synchronized (this) {
                acquired = null;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            synchronized (this) {
                if (acquired != null) {
                    try {
                        LOGGER.log(Level.WARNING, "InputStream created by ZipStorage#open() not closed properly", acquired);
                        acquired = null;
                        close();
                    } catch (Throwable ex) {
                        LOGGER.log(Level.WARNING, "Failed to finalize", ex);
                    }
                }
            }
        }
    }
}
