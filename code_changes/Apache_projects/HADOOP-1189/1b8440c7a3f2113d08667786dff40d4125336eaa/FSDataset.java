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
package org.apache.hadoop.dfs;

import java.io.*;
import java.util.*;

import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.util.DiskChecker.DiskOutOfSpaceException;
import org.apache.hadoop.conf.*;

/**************************************************
 * FSDataset manages a set of data blocks.  Each block
 * has a unique name and an extent on disk.
 *
 * @author Mike Cafarella
 ***************************************************/
class FSDataset implements FSConstants {


  /**
     * A node type that can be built into a tree reflecting the
     * hierarchy of blocks on the local disk.
     */
    class FSDir {
        File dir;
        int numBlocks = 0;
        FSDir children[];
        int lastChildIdx = 0;
        /**
         */
        public FSDir(File dir) 
            throws IOException {
            this.dir = dir;
            this.children = null;
            if (! dir.exists()) {
              if (! dir.mkdirs()) {
                throw new IOException("Mkdirs failed to create " + 
                                      dir.toString());
              }
            } else {
              File[] files = dir.listFiles();
              int numChildren = 0;
              for (int idx = 0; idx < files.length; idx++) {
                if (files[idx].isDirectory()) {
                  numChildren++;
                } else if (Block.isBlockFilename(files[idx])) {
                  numBlocks++;
                }
              }
              if (numChildren > 0) {
                children = new FSDir[numChildren];
                int curdir = 0;
                for (int idx = 0; idx < files.length; idx++) {
                  if (files[idx].isDirectory()) {
                    children[curdir] = new FSDir(files[idx]);
                    curdir++;
                  }
                }
              }
            }
        }
        
        public File addBlock( Block b, File src ) throws IOException {
          //First try without creating subdirectories
          File file = addBlock( b, src, false, false );          
          return ( file != null ) ? file : addBlock( b, src, true, true );
        }

        private File addBlock( Block b, File src, boolean createOk, 
                               boolean resetIdx ) throws IOException {
            if (numBlocks < maxBlocksPerDir) {
              File dest = new File(dir, b.getBlockName());
              src.renameTo(dest);
              numBlocks += 1;
              return dest;
            }
            
            if ( lastChildIdx < 0 && resetIdx ) {
              //reset so that all children will be checked
              lastChildIdx = random.nextInt( children.length );              
            }
            
            if ( lastChildIdx >= 0 && children != null ) {
              //Check if any child-tree has room for a block.
              for (int i=0; i < children.length; i++) {
                int idx = ( lastChildIdx + i )%children.length;
                File file = children[idx].addBlock( b, src, false, resetIdx );
                if ( file != null ) {
                  lastChildIdx = idx;
                  return file; 
                }
              }
              lastChildIdx = -1;
            }
            
            if ( !createOk ) {
              return null;
            }
            
            if ( children == null || children.length == 0 ) {
              children = new FSDir[maxBlocksPerDir];
              for (int idx = 0; idx < maxBlocksPerDir; idx++) {
                children[idx] = new FSDir( new File(dir, DataStorage.BLOCK_SUBDIR_PREFIX+idx) );
              }
            }
            
            //now pick a child randomly for creating a new set of subdirs.
            lastChildIdx = random.nextInt( children.length );
            return children[ lastChildIdx ].addBlock( b, src, true, false ); 
        }

        /**
         * Populate the given blockSet with any child blocks
         * found at this node.
         */
        public void getBlockInfo(TreeSet<Block> blockSet) {
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    children[i].getBlockInfo(blockSet);
                }
            }

            File blockFiles[] = dir.listFiles();
            for (int i = 0; i < blockFiles.length; i++) {
                if (Block.isBlockFilename(blockFiles[i])) {
                    blockSet.add(new Block(blockFiles[i], blockFiles[i].length()));
                }
            }
        }


        void getVolumeMap(HashMap<Block, FSVolume> volumeMap, FSVolume volume) {
          if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    children[i].getVolumeMap(volumeMap, volume);
                }
            }

            File blockFiles[] = dir.listFiles();
            for (int i = 0; i < blockFiles.length; i++) {
                if (Block.isBlockFilename(blockFiles[i])) {
                    volumeMap.put(new Block(blockFiles[i], blockFiles[i].length()), volume);
                }
            }
        }
        
        void getBlockMap(HashMap<Block, File> blockMap) {
          if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    children[i].getBlockMap(blockMap);
                }
            }

            File blockFiles[] = dir.listFiles();
            for (int i = 0; i < blockFiles.length; i++) {
                if (Block.isBlockFilename(blockFiles[i])) {
                    blockMap.put(new Block(blockFiles[i], blockFiles[i].length()), blockFiles[i]);
                }
            }
        }
        /**
         * check if a data diretory is healthy
         * @throws DiskErrorException
         * @author hairong
         */
        public void checkDirTree() throws DiskErrorException {
            DiskChecker.checkDir(dir);
            
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    children[i].checkDirTree();
                }
            }
        }
        
        void clearPath(File f) {
          String root = dir.getAbsolutePath();
          String dir = f.getAbsolutePath();
          if ( dir.startsWith( root ) ) {
            String[] dirNames = dir.substring( root.length() ).
                         split( File.separator + "subdir" );
            if ( clearPath( f, dirNames, 1 ) )
              return;
          }
          clearPath( f, null, -1 );
        }
        
        /*
         * dirNames is an array of string integers derived from
         * usual directory structure data/subdirN/subdirXY/subdirM ...
         * If dirName array is non-null, we only check the child at 
         * the children[dirNames[idx]]. This avoids iterating over
         * children in common case. If directory structure changes 
         * in later versions, we need to revisit this.
         */
        private boolean clearPath( File f, String[] dirNames, int idx ) {
          if ( ( dirNames == null || idx == dirNames.length ) &&
               dir.compareTo(f) == 0) {
            numBlocks--;
            return true;
          }
          
          if ( dirNames != null ) {
            //guess the child index from the directory name
            if ( idx > ( dirNames.length - 1 ) || children == null ) {
              return false;
            }
            int childIdx; 
            try {
              childIdx = Integer.parseInt( dirNames[idx] );
            } catch ( NumberFormatException ignored ) {
              // layout changed? we could print a warning.
              return false;
            }
            return ( childIdx >= 0 && childIdx < children.length ) ?
              children[childIdx].clearPath( f, dirNames, idx+1 ) : false;
          }

          //guesses failed. back to blind iteration.
          if ( children != null ) {
            for(int i=0; i < children.length; i++) {
              if ( children[i].clearPath( f, null, -1 ) ){
                return true;
              }
            }
          }
          return false;
        }
        
        public String toString() {
          return "FSDir{" +
              "dir=" + dir +
              ", children=" + (children == null ? null : Arrays.asList(children)) +
              "}";
        }
    }

    class FSVolume {
      static final double USABLE_DISK_PCT_DEFAULT = 0.98f; 

      private FSDir dataDir;
      private File tmpDir;
      private DF usage;
      private long reserved;
      private double usableDiskPct = USABLE_DISK_PCT_DEFAULT;
    
      FSVolume( File currentDir, Configuration conf) throws IOException {
        this.reserved = conf.getLong("dfs.datanode.du.reserved", 0);
        this.usableDiskPct = conf.getFloat("dfs.datanode.du.pct",
            (float) USABLE_DISK_PCT_DEFAULT);
        File parent = currentDir.getParentFile();
        this.dataDir = new FSDir( currentDir );
        this.tmpDir = new File(parent, "tmp");
        if (tmpDir.exists()) {
          FileUtil.fullyDelete(tmpDir);
        }
        if (!tmpDir.mkdirs()) {
          if (!tmpDir.isDirectory()) {
            throw new IOException("Mkdirs failed to create " + tmpDir.toString());
          }
        }
        this.usage = new DF(parent, conf);
      }
      
      long getCapacity() throws IOException {
        return usage.getCapacity();
      }
      
      long getAvailable() throws IOException {
        long capacity = usage.getCapacity();
        long freespace = Math.round(usage.getAvailableSkipRefresh() -
                                    capacity * (1 - usableDiskPct) - reserved); 
        return ( freespace > 0 ) ? freespace : 0;
      }
      
      String getMount() throws IOException {
        return usage.getMount();
      }
      
      File createTmpFile(Block b) throws IOException {
        File f = new File(tmpDir, b.getBlockName());
        try {
          if (f.exists()) {
            throw new IOException("Unexpected problem in creating temporary file for "+
                b + ".  File " + f + " should not be present, but is.");
          }
          // Create the zero-length temp file
          //
          if (!f.createNewFile()) {
            throw new IOException("Unexpected problem in creating temporary file for "+
                b + ".  File " + f + " should be creatable, but is already present.");
          }
        } catch (IOException ie) {
          System.out.println("Exception!  " + ie);
          throw ie;
        }
        return f;
      }
      
      File addBlock(Block b, File f) throws IOException {
        return dataDir.addBlock(b, f);
      }
      
      void checkDirs() throws DiskErrorException {
        dataDir.checkDirTree();
        DiskChecker.checkDir(tmpDir);
      }
      
      void getBlockInfo(TreeSet<Block> blockSet) {
        dataDir.getBlockInfo(blockSet);
      }
      
      void getVolumeMap(HashMap<Block, FSVolume> volumeMap) {
        dataDir.getVolumeMap(volumeMap, this);
      }
      
      void getBlockMap(HashMap<Block, File> blockMap) {
        dataDir.getBlockMap(blockMap);
      }
      
      void clearPath(File f) {
        dataDir.clearPath(f);
      }
      
      public String toString() {
        return dataDir.dir.getAbsolutePath();
      }
    }
    
    class FSVolumeSet {
      FSVolume[] volumes = null;
      int curVolume = 0;
      
      FSVolumeSet(FSVolume[] volumes) {
        this.volumes = volumes;
      }
      
      synchronized FSVolume getNextVolume(long blockSize) throws IOException {
        int startVolume = curVolume;
        while (true) {
          FSVolume volume = volumes[curVolume];
          curVolume = (curVolume + 1) % volumes.length;
          if (volume.getAvailable() >= blockSize) { return volume; }
          if (curVolume == startVolume) {
            throw new DiskOutOfSpaceException("Insufficient space for an additional block");
          }
        }
      }
      
      synchronized long getCapacity() throws IOException {
        long capacity = 0L;
        for (int idx = 0; idx < volumes.length; idx++) {
            capacity += volumes[idx].getCapacity();
        }
        return capacity;
      }
      
      synchronized long getRemaining() throws IOException {
        long remaining = 0L;
        for (int idx = 0; idx < volumes.length; idx++) {
          remaining += volumes[idx].getAvailable();
        }
        return remaining;
      }
      
      synchronized void getBlockInfo(TreeSet<Block> blockSet) {
        for (int idx = 0; idx < volumes.length; idx++) {
          volumes[idx].getBlockInfo(blockSet);
        }
      }
      
      synchronized void getVolumeMap(HashMap<Block, FSVolume> volumeMap) {
        for (int idx = 0; idx < volumes.length; idx++) {
          volumes[idx].getVolumeMap(volumeMap);
        }
      }
      
      synchronized void getBlockMap(HashMap<Block, File> blockMap) {
        for (int idx = 0; idx < volumes.length; idx++) {
          volumes[idx].getBlockMap(blockMap);
        }
      }
      
      synchronized void checkDirs() throws DiskErrorException {
        for (int idx = 0; idx < volumes.length; idx++) {
          volumes[idx].checkDirs();
        }
      }
      
      public String toString() {
        StringBuffer sb = new StringBuffer();
        for (int idx = 0; idx < volumes.length; idx++) {
          sb.append(volumes[idx].toString());
          if (idx != volumes.length - 1) { sb.append(","); }
        }
        return sb.toString();
      }
    }
    //////////////////////////////////////////////////////
    //
    // FSDataSet
    //
    //////////////////////////////////////////////////////

    FSVolumeSet volumes;
    private HashMap<Block,File> ongoingCreates = new HashMap<Block,File>();
    private int maxBlocksPerDir = 0;
    private HashMap<Block,FSVolume> volumeMap = null;
    private HashMap<Block,File> blockMap = null;
    static  Random random = new Random();

    /**
     * An FSDataset has a directory where it loads its data files.
     */
    public FSDataset( DataStorage storage, Configuration conf) throws IOException {
    	this.maxBlocksPerDir = conf.getInt("dfs.datanode.numblocks", 64);
        FSVolume[] volArray = new FSVolume[storage.getNumStorageDirs()];
        for (int idx = 0; idx < storage.getNumStorageDirs(); idx++) {
          volArray[idx] = new FSVolume(storage.getStorageDir(idx).getCurrentDir(), conf);
        }
        volumes = new FSVolumeSet(volArray);
        volumeMap = new HashMap<Block,FSVolume>();
        volumes.getVolumeMap(volumeMap);
        blockMap = new HashMap<Block,File>();
        volumes.getBlockMap(blockMap);
    }

    /**
     * Return total capacity, used and unused
     */
    public long getCapacity() throws IOException {
        return volumes.getCapacity();
    }

    /**
     * Return how many bytes can still be stored in the FSDataset
     */
    public long getRemaining() throws IOException {
        return volumes.getRemaining();
    }

    /**
     * Find the block's on-disk length
     */
    public long getLength(Block b) throws IOException {
        if (! isValidBlock(b)) {
            throw new IOException("Block " + b + " is not valid.");
        }
        File f = getFile(b);
        return f.length();
    }

    /**
     * Get a stream of data from the indicated block.
     */
    public synchronized InputStream getBlockData(Block b) throws IOException {
        if (! isValidBlock(b)) {
            throw new IOException("Block " + b + " is not valid.");
        }
        // File should be opened with the lock.
        return new FileInputStream(getFile(b));
    }

    /**
     * Start writing to a block file
     */
    public OutputStream writeToBlock(Block b) throws IOException {
        //
        // Make sure the block isn't a valid one - we're still creating it!
        //
        if (isValidBlock(b)) {
            throw new IOException("Block " + b + " is valid, and cannot be written to.");
        }
        long blockSize = b.getNumBytes();

        //
        // Serialize access to /tmp, and check if file already there.
        //
        File f = null;
        synchronized ( this ) {
            //
            // Is it already in the create process?
            //
            if (ongoingCreates.containsKey(b)) {
              // check how old is the temp file - wait 1 hour
              File tmp = (File)ongoingCreates.get(b);
              if ((System.currentTimeMillis() - tmp.lastModified()) < 3600 * 1000) {
                throw new IOException("Block " + b +
                    " has already been started (though not completed), and thus cannot be created.");
              } else {
                // stale temp file - remove
                if (!tmp.delete()) {
                  throw new IOException("Can't write the block - unable to remove stale temp file " + tmp);
                }
                ongoingCreates.remove(b);
              }
            }
            FSVolume v = null;
            synchronized ( volumes ) {
              v = volumes.getNextVolume(blockSize);
              // create temporary file to hold block in the designated volume
              f = v.createTmpFile(b);
            }
            ongoingCreates.put(b, f);
            volumeMap.put(b, v);
        }

        //
        // Finally, allow a writer to the block file
        // REMIND - mjc - make this a filter stream that enforces a max
        // block size, so clients can't go crazy
        //
        return new FileOutputStream(f);
    }

    //
    // REMIND - mjc - eventually we should have a timeout system
    // in place to clean up block files left by abandoned clients.
    // We should have some timer in place, so that if a blockfile
    // is created but non-valid, and has been idle for >48 hours,
    // we can GC it safely.
    //

    /**
     * Complete the block write!
     */
    public synchronized void finalizeBlock(Block b) throws IOException {
        File f = ongoingCreates.get(b);
        if (f == null || ! f.exists()) {
          throw new IOException("No temporary file " + f + " for block " + b);
        }
        long finalLen = f.length();
        b.setNumBytes(finalLen);
        FSVolume v = volumeMap.get(b);
        
        File dest = null;
        synchronized ( volumes ) {
          dest = v.addBlock(b, f);
        }
        blockMap.put(b, dest);
        ongoingCreates.remove(b);
    }

    /**
     * Return a table of block data
     */
    public Block[] getBlockReport() {
        TreeSet<Block> blockSet = new TreeSet<Block>();
        volumes.getBlockInfo(blockSet);
        Block blockTable[] = new Block[blockSet.size()];
        int i = 0;
        for (Iterator<Block> it = blockSet.iterator(); it.hasNext(); i++) {
            blockTable[i] = it.next();
        }
        return blockTable;
    }

    /**
     * Check whether the given block is a valid one.
     */
    public boolean isValidBlock(Block b) {
        File f = getFile(b);
        return (f!= null && f.exists());
    }

    /**
     * We're informed that a block is no longer valid.  We
     * could lazily garbage-collect the block, but why bother?
     * just get rid of it.
     */
    public void invalidate(Block invalidBlks[]) throws IOException {
      for (int i = 0; i < invalidBlks.length; i++) {
        File f;
        synchronized (this) {
          f = getFile(invalidBlks[i]);
          if (f == null) {
            throw new IOException("Unexpected error trying to delete block "
                                  + invalidBlks[i] + 
                                  ". Block not found in blockMap.");
          }
          FSVolume v = volumeMap.get(invalidBlks[i]);
          if (v == null) {
            throw new IOException("Unexpected error trying to delete block "
                                  + invalidBlks[i] + 
                                  ". No volume for this block.");
          }
          File parent = f.getParentFile();
          if (parent == null) {
            throw new IOException("Unexpected error trying to delete block "
                                  + invalidBlks[i] + 
                                  ". Parent not found for file " + f + ".");
          }
          v.clearPath(parent);
          blockMap.remove(invalidBlks[i]);
          volumeMap.remove(invalidBlks[i]);
        }
        if (!f.delete()) {
            throw new IOException("Unexpected error trying to delete block "
                                  + invalidBlks[i] + " at file " + f);
        }
        DataNode.LOG.info("Deleting block " + invalidBlks[i]);
      }
    }

    /**
     * Turn the block identifier into a filename.
     */
    synchronized File getFile(Block b) {
      return blockMap.get(b);
    }

    /**
     * check if a data diretory is healthy
     * @throws DiskErrorException
     * @author hairong
     */
    void checkDataDir() throws DiskErrorException {
        volumes.checkDirs();
    }
    

    public String toString() {
      return "FSDataset{dirpath='"+volumes+"'}";
    }

}
