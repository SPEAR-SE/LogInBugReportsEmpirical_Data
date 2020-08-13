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
package org.apache.hadoop.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Progressable;

/** An implementation of the in-memory filesystem. This implementation assumes
 * that the file lengths are known ahead of time and the total lengths of all
 * the files is below a certain number (like 100 MB, configurable). Use the API
 * reserveSpaceWithCheckSum(Path f, int size) (see below for a description of
 * the API for reserving space in the FS. The uri of this filesystem starts with
 * ramfs:// .
 * @author ddas
 *
 */
public class InMemoryFileSystem extends ChecksumFileSystem {
  private static class RawInMemoryFileSystem extends FileSystem {
  private URI uri;
  private int fsSize;
  private volatile int totalUsed;
  private Path staticWorkingDir;
  
  //pathToFileAttribs is the final place where a file is put after it is closed
  private Map <String, FileAttributes> pathToFileAttribs = 
    Collections.synchronizedMap(new HashMap());
  
  //tempFileAttribs is a temp place which is updated while reserving memory for
  //files we are going to create. It is read in the createRaw method and the
  //temp key/value is discarded. If the file makes it to "close", then it
  //ends up being in the pathToFileAttribs map.
  private Map <String, FileAttributes> tempFileAttribs = 
    Collections.synchronizedMap(new HashMap());
  
  public RawInMemoryFileSystem() {
    setConf(new Configuration());
  }

  public RawInMemoryFileSystem(URI uri, Configuration conf) {
    initialize(uri, conf);
  }
  
  //inherit javadoc
  public void initialize(URI uri, Configuration conf) {
    setConf(conf);
    int size = Integer.parseInt(conf.get("fs.inmemory.size.mb", "100"));
    this.fsSize = size * 1024 * 1024;
    this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());
    this.staticWorkingDir = new Path(this.uri.getPath());
    LOG.info("Initialized InMemoryFileSystem: " + uri.toString() + 
             " of size (in bytes): " + fsSize);
  }

  //inherit javadoc
  public URI getUri() {
    return uri;
  }

  /** @deprecated */
  public String getName() {
    return uri.toString();
  }

  /**
   * Return 1x1 'inmemory' cell if the file exists.
   * Return null if otherwise.
   */
  public String[][] getFileCacheHints(Path f, long start, long len)
      throws IOException {
    if (! exists(f)) {
      return null;
    } else {
      return new String[][] {{"inmemory"}};
    }
  }

  private class InMemoryInputStream extends FSInputStream {
    private DataInputBuffer din = new DataInputBuffer();
    private FileAttributes fAttr;
    
    public InMemoryInputStream(Path f) throws IOException {
      synchronized (RawInMemoryFileSystem.this) {
        fAttr = pathToFileAttribs.get(getPath(f));
        if (fAttr == null) { 
          throw new FileNotFoundException("File " + f + " does not exist");
        }                            
        din.reset(fAttr.data, 0, fAttr.size);
      }
    }
    
    public long getPos() throws IOException {
      return din.getPosition();
    }
    
    public void seek(long pos) throws IOException {
      if ((int)pos > fAttr.size)
        throw new IOException("Cannot seek after EOF");
      din.reset(fAttr.data, (int)pos, fAttr.size - (int)pos);
    }
    
    public boolean seekToNewSource(long targetPos) throws IOException {
      return false;
    }

    public int available() throws IOException {
      return din.available(); 
    }
    public boolean markSupport() { return false; }

    public int read() throws IOException {
      return din.read();
    }

    public int read(byte[] b, int off, int len) throws IOException {
      return din.read(b, off, len);
    }
    
    public long skip(long n) throws IOException { return din.skip(n); }
  }

  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    return new FSDataInputStream(new InMemoryInputStream(f), bufferSize);
  }

  private class InMemoryOutputStream extends OutputStream {
    private int count;
    private FileAttributes fAttr;
    private Path f;
    
    public InMemoryOutputStream(Path f, FileAttributes fAttr) 
    throws IOException {
      this.fAttr = fAttr;
      this.f = f;
    }
    
    public long getPos() throws IOException {
      return count;
    }
    
    public void close() throws IOException {
      synchronized (RawInMemoryFileSystem.this) {
        pathToFileAttribs.put(getPath(f), fAttr);
      }
    }
    
    public void write(byte[] b, int off, int len) throws IOException {
      if ((off < 0) || (off > b.length) || (len < 0) ||
          ((off + len) > b.length) || ((off + len) < 0)) {
        throw new IndexOutOfBoundsException();
      } else if (len == 0) {
        return;
      }
      int newcount = count + len;
      if (newcount > fAttr.size) {
        throw new IOException("Insufficient space");
      }
      System.arraycopy(b, off, fAttr.data, count, len);
      count = newcount;
    }
    
    public void write(int b) throws IOException {
      int newcount = count + 1;
      if (newcount > fAttr.size) {
        throw new IOException("Insufficient space");
      }
      fAttr.data[count] = (byte)b;
      count = newcount;
    }
  }
  
  public FSDataOutputStream create(Path f, boolean overwrite, int bufferSize,
      short replication, long blockSize, Progressable progress)
      throws IOException {
    if (exists(f) && ! overwrite) {
      throw new IOException("File already exists:"+f);
    }
    synchronized (this) {
      FileAttributes fAttr =(FileAttributes) tempFileAttribs.remove(getPath(f));
      if (fAttr != null)
        return create(f, fAttr);
      return null;
    }
  }
  
  public FSDataOutputStream create(Path f, FileAttributes fAttr)
      throws IOException {
    // the path is not added into the filesystem (in the pathToFileAttribs
    // map) until close is called on the outputstream that this method is
    // going to return
    // Create an output stream out of data byte array
    return new FSDataOutputStream(new InMemoryOutputStream(f, fAttr),
        getConf());
  }

  public void close() throws IOException {
    super.close();
    synchronized (this) {
      if (pathToFileAttribs != null) { 
        pathToFileAttribs.clear();
      }
      pathToFileAttribs = null;
      if (tempFileAttribs != null) {
        tempFileAttribs.clear();
      }
      tempFileAttribs = null;
    }
  }

  /**
   * Replication is not supported for the inmemory file system.
   */
  public short getReplication(Path src) throws IOException {
    return 1;
  }

  public boolean setReplication(Path src, short replication)
      throws IOException {
    return true;
  }

  public boolean rename(Path src, Path dst) throws IOException {
    synchronized (this) {
      if (exists(dst)) {
        throw new IOException ("Path " + dst + " already exists");
      }
      FileAttributes fAttr = pathToFileAttribs.remove(getPath(src));
      if (fAttr == null) return false;
      pathToFileAttribs.put(getPath(dst), fAttr);
      return true;
    }
  }

  public boolean delete(Path f) throws IOException {
    synchronized (this) {
      FileAttributes fAttr = pathToFileAttribs.remove(getPath(f));
      if (fAttr != null) {
        fAttr.data = null;
        totalUsed -= fAttr.size;
        return true;
      }
      return false;
    }
  }

  public boolean exists(Path f) throws IOException {
    synchronized (this) {
      return pathToFileAttribs.containsKey(getPath(f));
    }
  }
  
  /**
   * Directory operations are not supported
   */
  public boolean isDirectory(Path f) throws IOException {
    return !isFile(f);
  }

  public boolean isFile(Path f) throws IOException {
    return exists(f);
  }

  public long getLength(Path f) throws IOException {
    synchronized (this) {
      return pathToFileAttribs.get(getPath(f)).size;
    }
  }
  
  /**
   * Directory operations are not supported
   */
  public Path[] listPaths(Path f) throws IOException {
    return null;
  }

  public void setWorkingDirectory(Path new_dir) {
    staticWorkingDir = new_dir;
  }
  
  public Path getWorkingDirectory() {
    return staticWorkingDir;
  }

  public boolean mkdirs(Path f) throws IOException {
    return true;
  }
  
  /** lock operations are not supported */
  public void lock(Path f, boolean shared) throws IOException {}
  public void release(Path f) throws IOException {}
  
  /** copy/move operations are not supported */
  public void copyFromLocalFile(boolean delSrc, Path src, Path dst)
      throws IOException {
  }

  public void copyToLocalFile(boolean delSrc, Path src, Path dst)
      throws IOException {
  }

  public Path startLocalOutput(Path fsOutputFile, Path tmpLocalFile)
      throws IOException {
    return fsOutputFile;
  }

  public void completeLocalOutput(Path fsOutputFile, Path tmpLocalFile)
      throws IOException {
  }

  public long getBlockSize(Path f) throws IOException {
    return getDefaultBlockSize();
  }

  public long getDefaultBlockSize() {
    return 32 * 1024; //some random large number. can be anything actually
  }

  public short getDefaultReplication() {
    return 1;
  }
  
  /** Some APIs exclusively for InMemoryFileSystem */
  
  /** Register a path with its size. */
  public boolean reserveSpace(Path f, int size) {
    synchronized (this) {
      if (!canFitInMemory(size))
        return false;
      FileAttributes fileAttr;
      try {
        fileAttr = new FileAttributes(size);
      } catch (OutOfMemoryError o) {
        return false;
      }
      totalUsed += size;
      tempFileAttribs.put(getPath(f), fileAttr);
      return true;
    }
  }
  
  /** This API getClosedFiles could have been implemented over listPathsRaw
   * but it is an overhead to maintain directory structures for this impl of
   * the in-memory fs.
   */
  public Path[] getFiles(PathFilter filter) {
    synchronized (this) {
      List<String> closedFilesList = new ArrayList<String>();
      synchronized (pathToFileAttribs) {
        Set paths = pathToFileAttribs.keySet();
        if (paths == null || paths.isEmpty()) {
          return new Path[0];
        }
        Iterator iter = paths.iterator();
        while (iter.hasNext()) {
          String f = (String)iter.next();
          if (filter.accept(new Path(f))) {
            closedFilesList.add(f);
          }
        }
      }
      String [] names = 
        closedFilesList.toArray(new String[closedFilesList.size()]);
      Path [] results = new Path[names.length];
      for (int i = 0; i < names.length; i++) {
        results[i] = new Path(names[i]);
      }
      return results;
    }
  }
  
  public int getNumFiles(PathFilter filter) {
    return getFiles(filter).length;
  }

  public int getFSSize() {
    return fsSize;
  }
  
  public float getPercentUsed() {
    if (fsSize > 0)
      return (float)totalUsed/fsSize;
    else return 0.1f;
  }
 
  private boolean canFitInMemory(int size) {
    if (size + totalUsed < fsSize)
      return true;
    return false;
  }
  
  private String getPath(Path f) {
    return f.toUri().getPath();
  }
  
  private static class FileAttributes {
    private byte[] data;
    private int size;
    
    public FileAttributes(int size) {
      this.size = size;
      this.data = new byte[size];
    }
  }
  }
    
    public InMemoryFileSystem() {
        super(new RawInMemoryFileSystem());
    }
    
    public InMemoryFileSystem(URI uri, Configuration conf) {
        super(new RawInMemoryFileSystem(uri, conf));
    }
    
    /** copy/move operations are not supported */
    public void copyFromLocalFile(boolean delSrc, Path src, Path dst)
    throws IOException {}
    public void copyToLocalFile(boolean delSrc, Path src, Path dst)
    throws IOException {}
    
    public Path startLocalOutput(Path fsOutputFile, Path tmpLocalFile)
    throws IOException {
        return fsOutputFile;
    }
    
    public void completeLocalOutput(Path fsOutputFile, Path tmpLocalFile)
    throws IOException {
    }
    
    public void reportChecksumFailure(Path p, FSDataInputStream in,
            long inPos,
            FSDataInputStream sums, long sumsPos) {
    }
    
    /**
     * Register a file with its size. This will also register a checksum for the
     * file that the user is trying to create. This is required since none of
     * the FileSystem APIs accept the size of the file as argument. But since it
     * is required for us to apriori know the size of the file we are going to
     * create, the user must call this method for each file he wants to create
     * and reserve memory for that file. We either succeed in reserving memory
     * for both the main file and the checksum file and return true, or return
     * false.
     */
    public boolean reserveSpaceWithCheckSum(Path f, int size) {
        // get the size of the checksum file (we know it is going to be 'int'
        // since this is an inmem fs with file sizes that will fit in 4 bytes)
        long checksumSize = getChecksumFileLength(f, size);
        RawInMemoryFileSystem mfs = (RawInMemoryFileSystem)getRawFileSystem();
        synchronized(mfs) {
            return mfs.reserveSpace(f, size) && 
            mfs.reserveSpace(getChecksumFile(f),
                    (int)getChecksumFileLength(f, size));
        }
    }
    public Path[] getFiles(PathFilter filter) {
        return ((RawInMemoryFileSystem)getRawFileSystem()).getFiles(filter);
    }
    
    public int getNumFiles(PathFilter filter) {
      return ((RawInMemoryFileSystem)getRawFileSystem()).getNumFiles(filter);
    }

    public int getFSSize() {
        return ((RawInMemoryFileSystem)getRawFileSystem()).getFSSize();
    }
    
    public float getPercentUsed() {
        return ((RawInMemoryFileSystem)getRawFileSystem()).getPercentUsed();
    }
}
