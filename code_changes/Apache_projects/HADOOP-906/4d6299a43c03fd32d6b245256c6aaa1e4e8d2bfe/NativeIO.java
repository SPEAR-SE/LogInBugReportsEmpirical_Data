/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.io.nativeio;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.SecureIOUtils.AlreadyExistsException;
import org.apache.hadoop.util.NativeCodeLoader;
import org.apache.hadoop.util.Shell;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
/**
 * JNI wrappers for various native IO-related calls not available in Java.
 * These functions should generally be used alongside a fallback to another
 * more portable mechanism.
 */
public class NativeIO {
  public static class POSIX {
    // Flags for open() call from bits/fcntl.h
    public static final int O_RDONLY = 00;
    public static final int O_WRONLY = 01;
    public static final int O_RDWR = 02;
    public static final int O_CREAT = 0100;
    public static final int O_EXCL = 0200;
    public static final int O_NOCTTY = 0400;
    public static final int O_TRUNC = 01000;
    public static final int O_APPEND = 02000;
    public static final int O_NONBLOCK = 04000;
    public static final int O_SYNC = 010000;
    public static final int O_ASYNC = 020000;
    public static final int O_FSYNC = O_SYNC;
    public static final int O_NDELAY = O_NONBLOCK;
    
    /** Wrapper around open(2) */
    public static native FileDescriptor open(String path, int flags, int mode) throws IOException;
    
    /** Wrapper around fstat(2) */
    //TODO: fstat is an old implementation. Doesn't use the cache. This should be 
    //changed to use the cache.
    public static native Stat fstat(FileDescriptor fd) throws IOException;

    /** Native chmod implementation. On Linux, it is a wrapper around chmod(2) */
    private static native void chmodImpl(String path, int mode) throws IOException;

    public static void chmod(String path, int mode) throws IOException {
      if (!Shell.WINDOWS) {
        chmodImpl(path, mode);
      } else {
        try {
          chmodImpl(path, mode);
        } catch (NativeIOException nioe) {
          if (nioe.getErrorCode() == 3) {
            throw new NativeIOException("No such file or directory",
                Errno.ENOENT);
          } else {
            LOG.warn(String.format("NativeIO.chmod error (%d): %s",
                nioe.getErrorCode(), nioe.getMessage()));
            throw new NativeIOException("Unknown error", Errno.UNKNOWN);
          }
        }
      }
    }

    /** Linux only methods used for getOwner() implementation */
    private static native long getUIDforFDOwnerforOwner(FileDescriptor fd) throws IOException;
    private static native String getUserName(long uid) throws IOException;
    
    /**
     * Result type of the fstat call
     */
    public static class Stat {
      private String owner;
      private int mode;

      // Mode constants
      public static final int S_IFMT = 0170000;      /* type of file */
      public static final int   S_IFIFO  = 0010000;  /* named pipe (fifo) */
      public static final int   S_IFCHR  = 0020000;  /* character special */
      public static final int   S_IFDIR  = 0040000;  /* directory */
      public static final int   S_IFBLK  = 0060000;  /* block special */
      public static final int   S_IFREG  = 0100000;  /* regular */
      public static final int   S_IFLNK  = 0120000;  /* symbolic link */
      public static final int   S_IFSOCK = 0140000;  /* socket */
      public static final int   S_IFWHT  = 0160000;  /* whiteout */
      public static final int S_ISUID = 0004000;  /* set user id on execution */
      public static final int S_ISGID = 0002000;  /* set group id on execution */
      public static final int S_ISVTX = 0001000;  /* save swapped text even after use */
      public static final int S_IRUSR = 0000400;  /* read permission, owner */
      public static final int S_IWUSR = 0000200;  /* write permission, owner */
      public static final int S_IXUSR = 0000100;  /* execute/search permission, owner */

      public Stat(String owner, int mode) {
        this.owner = owner;
        this.mode = mode;
      }

      public String toString() {
        return "Stat(owner='" + owner + "'" +
          ", mode=" + mode + ")";
      }

      public String getOwner() {
        return owner;
      }
      public int getMode() {
        return mode;
      }
    }

    static {
      if (NativeCodeLoader.isNativeCodeLoaded()) {
        try {
          initNative();
          nativeLoaded = true;
        } catch (Throwable t) {
          // This can happen if the user has an older version of libhadoop.so
          // installed - in this case we can continue without native IO
          // after warning
          LOG.error("Unable to initialize NativeIO libraries", t);
        }
      }
    }
  }
  
  public static class Windows {
    // Flags for CreateFile() call on Windows
    public static final long GENERIC_READ = 0x80000000L;
    public static final long GENERIC_WRITE = 0x40000000L;

    public static final long FILE_SHARE_READ = 0x00000001L;
    public static final long FILE_SHARE_WRITE = 0x00000002L;
    public static final long FILE_SHARE_DELETE = 0x00000004L;

    public static final long CREATE_NEW = 1;
    public static final long CREATE_ALWAYS = 2;
    public static final long OPEN_EXISTING = 3;
    public static final long OPEN_ALWAYS = 4;
    public static final long TRUNCATE_EXISTING = 5;
    
    public static final long FILE_BEGIN = 0;
    public static final long FILE_CURRENT = 1;
    public static final long FILE_END = 2;

    /** Wrapper around CreateFile() on Windows */
    public static native FileDescriptor createFile(String path,
        long desiredAccess, long shareMode, long creationDisposition)
        throws IOException;

    /** Wrapper around SetFilePointer() on Windows */
    public static native long setFilePointer(FileDescriptor fd,
        long distanceToMove, long moveMethod) throws IOException;
    
    /** Windows only methods used for getOwner() implementation */
    private static native String getOwner(FileDescriptor fd) throws IOException;

    static {
      if (NativeCodeLoader.isNativeCodeLoaded()) {
        try {
          initNative();
          nativeLoaded = true;
        } catch (Throwable t) {
          // This can happen if the user has an older version of libhadoop.so
          // installed - in this case we can continue without native IO
          // after warning
          LOG.error("Unable to initialize NativeIO libraries", t);
        }
      }
    }
  }

  private static final Log LOG = LogFactory.getLog(NativeIO.class);

  private static boolean nativeLoaded = false;

  static {
    if (NativeCodeLoader.isNativeCodeLoaded()) {
      try {
        initNative();
        nativeLoaded = true;
      } catch (Throwable t) {
        // This can happen if the user has an older version of libhadoop.so
        // installed - in this case we can continue without native IO
        // after warning
        LOG.error("Unable to initialize NativeIO libraries", t);
      }
    }
  }

  /**
   * Return true if the JNI-based native IO extensions are available.
   */
  public static boolean isAvailable() {
    return NativeCodeLoader.isNativeCodeLoaded() && nativeLoaded;
  }

  /** Initialize the JNI method ID and class ID cache */
  private static native void initNative();

  private static class CachedUid {
    final long timestamp;
    final String username;
    public CachedUid(String username, long timestamp) {
      this.timestamp = timestamp;
      this.username = username;
    }
  }
  private static final Map<Long, CachedUid> uidCache = 
    new ConcurrentHashMap<Long, CachedUid>();
  private static long cacheTimeout;
  private static boolean initialized = false;
  
  public static String getOwner(FileDescriptor fd) throws IOException {
    ensureInitialized();
    if (Shell.WINDOWS) {
      String owner = Windows.getOwner(fd);
      int i = owner.indexOf('\\');
      if (i != -1)
        owner = owner.substring(i + 1);
      return owner;
    } else {
      long uid = POSIX.getUIDforFDOwnerforOwner(fd);
      CachedUid cUid = uidCache.get(uid);
      long now = System.currentTimeMillis();
      if (cUid != null && (cUid.timestamp + cacheTimeout) > now) {
        return cUid.username;
      }
      String user = POSIX.getUserName(uid);
      LOG.info("Got UserName " + user + " for UID " + uid
          + " from the native implementation");
      cUid = new CachedUid(user, now);
      uidCache.put(uid, cUid);
      return user;
    }
  }

  /**
   * Create a FileInputStream that shares delete permission on the
   * file opened, i.e. other process can delete the file the
   * FileInputStream is reading. Only Windows implementation uses
   * the native interface.
   */
  public static FileInputStream getShareDeleteFileInputStream(File f)
      throws IOException {
    if (!Shell.WINDOWS) {
      // On Linux the default FileInputStream shares delete permission
      // on the file opened.
      //
      return new FileInputStream(f);
    } else {
      // Use Windows native interface to create a FileInputStream that
      // shares delete permission on the file opened.
      //
      FileDescriptor fd = Windows.createFile(
          f.getAbsolutePath(),
          Windows.GENERIC_READ,
          Windows.FILE_SHARE_READ |
          Windows.FILE_SHARE_WRITE |
          Windows.FILE_SHARE_DELETE,
          Windows.OPEN_EXISTING);
      return new FileInputStream(fd);
    }
  }

  /**
   * Create a FileInputStream that shares delete permission on the
   * file opened at a given offset, i.e. other process can delete
   * the file the FileInputStream is reading. Only Windows implementation
   * uses the native interface.
   */
  public static FileInputStream getShareDeleteFileInputStream(File f, long seekOffset)
      throws IOException {
    if (!Shell.WINDOWS) {
      RandomAccessFile rf = new RandomAccessFile(f, "r");
      if (seekOffset > 0) {
        rf.seek(seekOffset);
      }
      return new FileInputStream(rf.getFD());
    } else {
      // Use Windows native interface to create a FileInputStream that
      // shares delete permission on the file opened, and set it to the
      // given offset.
      //
      FileDescriptor fd = NativeIO.Windows.createFile(
          f.getAbsolutePath(),
          NativeIO.Windows.GENERIC_READ,
          NativeIO.Windows.FILE_SHARE_READ |
          NativeIO.Windows.FILE_SHARE_WRITE |
          NativeIO.Windows.FILE_SHARE_DELETE,
          NativeIO.Windows.OPEN_EXISTING);
      if (seekOffset > 0)
        NativeIO.Windows.setFilePointer(fd, seekOffset, NativeIO.Windows.FILE_BEGIN);
      return new FileInputStream(fd);
    }
  }

  /**
   * Create the specified File for write access, ensuring that it does not exist.
   * @param f the file that we want to create
   * @param permissions we want to have on the file (if security is enabled)
   *
   * @throws AlreadyExistsException if the file already exists
   * @throws IOException if any other error occurred
   */
  public static FileOutputStream getCreateForWriteFileOutputStream(File f, int permissions)
  throws IOException {
    if (!Shell.WINDOWS) {
      // Use the native wrapper around open(2)
      try {
        FileDescriptor fd = NativeIO.POSIX.open(f.getAbsolutePath(),
            NativeIO.POSIX.O_WRONLY | NativeIO.POSIX.O_CREAT
                | NativeIO.POSIX.O_EXCL, permissions);
        return new FileOutputStream(fd);
      } catch (NativeIOException nioe) {
        if (nioe.getErrno() == Errno.EEXIST) {
          throw new AlreadyExistsException(nioe);
        }
        throw nioe;
      }
    } else {
      // Use the Windows native APIs to create equivalent FileOutputStream
      try {
        FileDescriptor fd = NativeIO.Windows.createFile(f.getCanonicalPath(),
            NativeIO.Windows.GENERIC_WRITE,
            NativeIO.Windows.FILE_SHARE_DELETE
                | NativeIO.Windows.FILE_SHARE_READ
                | NativeIO.Windows.FILE_SHARE_WRITE,
            NativeIO.Windows.CREATE_NEW);
        NativeIO.POSIX.chmod(f.getCanonicalPath(), permissions);
        return new FileOutputStream(fd);
      } catch (NativeIOException nioe) {
        if (nioe.getErrorCode() == 80) {
          // ERROR_FILE_EXISTS
          // 80 (0x50)
          // The file exists
          throw new AlreadyExistsException(nioe);
        }
        throw nioe;
      }
    }
  }

  private synchronized static void ensureInitialized() {
    if (!initialized) {
      cacheTimeout = 
        new Configuration().getLong("hadoop.security.uid.cache.secs", 
                                     4*60*60) * 1000;
      LOG.info("Initialized cache for UID to User mapping with a cache" +
        " timeout of " + cacheTimeout/1000 + " seconds.");
      initialized = true;
    }
  }
}
