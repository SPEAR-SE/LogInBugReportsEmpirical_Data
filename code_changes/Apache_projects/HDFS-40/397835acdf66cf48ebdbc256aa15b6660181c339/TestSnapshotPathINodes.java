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
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory.INodesInPath;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeDirectorySnapshottable;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeDirectoryWithSnapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.INodeFileSnapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/** Test snapshot related operations. */
public class TestSnapshotPathINodes {
  private static final long seed = 0;
  private static final short REPLICATION = 3;

  static private final Path dir = new Path("/TestSnapshot");
  
  static private final Path sub1 = new Path(dir, "sub1");
  static private final Path file1 = new Path(sub1, "file1");
  static private final Path file2 = new Path(sub1, "file2");

  static private Configuration conf;
  static private MiniDFSCluster cluster;
  static private FSNamesystem fsn;
  static private FSDirectory fsdir;

  static private DistributedFileSystem hdfs;

  @BeforeClass
  static public void setUp() throws Exception {
    conf = new Configuration();
    cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(REPLICATION)
      .build();
    cluster.waitActive();
    
    fsn = cluster.getNamesystem();
    fsdir = fsn.getFSDirectory();
    
    hdfs = cluster.getFileSystem();
    DFSTestUtil.createFile(hdfs, file1, 1024, REPLICATION, seed);
    DFSTestUtil.createFile(hdfs, file2, 1024, REPLICATION, seed);
  }

  @AfterClass
  static public void tearDown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  /** Test allow-snapshot operation. */
  @Test
  public void testAllowSnapshot() throws Exception {
    final String path = sub1.toString();
    final INode before = fsdir.getINode(path);
    
    // Before a directory is snapshottable
    Assert.assertTrue(before instanceof INodeDirectory);
    Assert.assertFalse(before instanceof INodeDirectorySnapshottable);

    // After a directory is snapshottable
    hdfs.allowSnapshot(path);
    {
      final INode after = fsdir.getINode(path);
      Assert.assertTrue(after instanceof INodeDirectorySnapshottable);
    }
    
    hdfs.disallowSnapshot(path);
    {
      final INode after = fsdir.getINode(path);
      Assert.assertTrue(after instanceof INodeDirectory);
      Assert.assertFalse(after instanceof INodeDirectorySnapshottable);
    }
  }
  
  static Snapshot getSnapshot(INodesInPath inodesInPath, String name) {
    if (name == null) {
      return null;
    }
    final int i = inodesInPath.getSnapshotRootIndex() - 1;
    final INode inode = inodesInPath.getINodes()[i];
    return ((INodeDirectorySnapshottable)inode).getSnapshot(
        DFSUtil.string2Bytes(name)); 
  }

  static void assertSnapshot(INodesInPath inodesInPath, boolean isSnapshot,
      final Snapshot snapshot, int index) {
    assertEquals(isSnapshot, inodesInPath.isSnapshot());
    assertEquals(index, inodesInPath.getSnapshotRootIndex());
    assertEquals(isSnapshot? snapshot: null, inodesInPath.getPathSnapshot());
    assertEquals(isSnapshot? null: snapshot, inodesInPath.getLatestSnapshot());
    if (isSnapshot && index >= 0) {
      assertEquals(Snapshot.Root.class, inodesInPath.getINodes()[index].getClass());
    }
  }

  static void assertINodeFile(INode inode, Path path) {
    assertEquals(path.getName(), inode.getLocalName());
    assertEquals(INodeFile.class, inode.getClass());
  }

  /** 
   * Test {@link INodeDirectory#getExistingPathINodes(byte[][], int, boolean)} 
   * for normal (non-snapshot) file.
   */
  @Test
  public void testNonSnapshotPathINodes() throws Exception {
    // Get the inodes by resolving the path of a normal file
    String[] names = INode.getPathNames(file1.toString());
    byte[][] components = INode.getPathComponents(names);
    INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
        components.length, false);
    INode[] inodes = nodesInPath.getINodes();
    // The number of inodes should be equal to components.length
    assertEquals(inodes.length, components.length);
    // The returned nodesInPath should be non-snapshot
    assertSnapshot(nodesInPath, false, null, -1);

    // The last INode should be associated with file1
    assertTrue("file1=" + file1 + ", nodesInPath=" + nodesInPath,
        inodes[components.length - 1] != null);
    assertEquals(inodes[components.length - 1].getFullPathName(),
        file1.toString());
    assertEquals(inodes[components.length - 2].getFullPathName(),
        sub1.toString());
    assertEquals(inodes[components.length - 3].getFullPathName(),
        dir.toString());
    
    // Call getExistingPathINodes and request only one INode. This is used
    // when identifying the INode for a given path.
    nodesInPath = fsdir.rootDir.getExistingPathINodes(components, 1, false);
    inodes = nodesInPath.getINodes();
    assertEquals(inodes.length, 1);
    assertSnapshot(nodesInPath, false, null, -1);
    assertEquals(inodes[0].getFullPathName(), file1.toString());
    
    // Call getExistingPathINodes and request 2 INodes. This is usually used
    // when identifying the parent INode of a given path.
    nodesInPath = fsdir.rootDir.getExistingPathINodes(components, 2, false);
    inodes = nodesInPath.getINodes();
    assertEquals(inodes.length, 2);
    assertSnapshot(nodesInPath, false, null, -1);
    assertEquals(inodes[1].getFullPathName(), file1.toString());
    assertEquals(inodes[0].getFullPathName(), sub1.toString());
  }
  
  /** 
   * Test {@link INodeDirectory#getExistingPathINodes(byte[][], int, boolean)} 
   * for snapshot file.
   */
  @Test
  public void testSnapshotPathINodes() throws Exception {
    // Create a snapshot for the dir, and check the inodes for the path
    // pointing to a snapshot file
    hdfs.allowSnapshot(sub1.toString());
    hdfs.createSnapshot(sub1, "s1");
    // The path when accessing the snapshot file of file1 is
    // /TestSnapshot/sub1/.snapshot/s1/file1
    String snapshotPath = sub1.toString() + "/.snapshot/s1/file1";
    String[] names = INode.getPathNames(snapshotPath);
    byte[][] components = INode.getPathComponents(names);
    INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
        components.length, false);
    INode[] inodes = nodesInPath.getINodes();
    // Length of inodes should be (components.length - 1), since we will ignore
    // ".snapshot" 
    assertEquals(inodes.length, components.length - 1);
    // SnapshotRootIndex should be 3: {root, Testsnapshot, sub1, s1, file1}
    final Snapshot snapshot = getSnapshot(nodesInPath, "s1");
    assertSnapshot(nodesInPath, true, snapshot, 3);
    // Check the INode for file1 (snapshot file)
    INode snapshotFileNode = inodes[inodes.length - 1]; 
    assertINodeFile(snapshotFileNode, file1);
    assertTrue(snapshotFileNode.getParent() instanceof 
        INodeDirectoryWithSnapshot);
    
    // Call getExistingPathINodes and request only one INode.
    nodesInPath = fsdir.rootDir.getExistingPathINodes(components, 1, false);
    inodes = nodesInPath.getINodes();
    assertEquals(inodes.length, 1);
    // The snapshotroot (s1) is not included in inodes. Thus the
    // snapshotRootIndex should be -1.
    assertSnapshot(nodesInPath, true, snapshot, -1);
    // Check the INode for file1 (snapshot file)
    assertINodeFile(nodesInPath.getLastINode(), file1);
    
    // Call getExistingPathINodes and request 2 INodes.
    nodesInPath = fsdir.rootDir.getExistingPathINodes(components, 2, false);
    inodes = nodesInPath.getINodes();
    assertEquals(inodes.length, 2);
    // There should be two INodes in inodes: s1 and snapshot of file1. Thus the
    // SnapshotRootIndex should be 0.
    assertSnapshot(nodesInPath, true, snapshot, 0);
    assertINodeFile(nodesInPath.getLastINode(), file1);
    
    // Resolve the path "/TestSnapshot/sub1/.snapshot"  
    String dotSnapshotPath = sub1.toString() + "/.snapshot";
    names = INode.getPathNames(dotSnapshotPath);
    components = INode.getPathComponents(names);
    nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
        components.length, false);
    inodes = nodesInPath.getINodes();
    // The number of INodes returned should be components.length - 1 since we
    // will ignore ".snapshot"
    assertEquals(inodes.length, components.length - 1);

    // No SnapshotRoot dir is included in the resolved inodes  
    assertSnapshot(nodesInPath, true, snapshot, -1);
    // The last INode should be the INode for sub1
    assertEquals(inodes[inodes.length - 1].getFullPathName(), sub1.toString());
    assertFalse(inodes[inodes.length - 1] instanceof INodeFileSnapshot);
  }
  
  /** 
   * Test {@link INodeDirectory#getExistingPathINodes(byte[][], int, boolean)} 
   * for snapshot file after deleting the original file.
   */
  @Test
  public void testSnapshotPathINodesAfterDeletion() throws Exception {
    // Create a snapshot for the dir, and check the inodes for the path
    // pointing to a snapshot file
    hdfs.allowSnapshot(sub1.toString());
    hdfs.createSnapshot(sub1, "s2");
    
    // Delete the original file /TestSnapshot/sub1/file1
    hdfs.delete(file1, false);
    
    final Snapshot snapshot;
    {
      // Resolve the path for the snapshot file
      // /TestSnapshot/sub1/.snapshot/s2/file1
      String snapshotPath = sub1.toString() + "/.snapshot/s2/file1";
      String[] names = INode.getPathNames(snapshotPath);
      byte[][] components = INode.getPathComponents(names);
      INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
          components.length, false);
      INode[] inodes = nodesInPath.getINodes();
      // Length of inodes should be (components.length - 1), since we will ignore
      // ".snapshot" 
      assertEquals(inodes.length, components.length - 1);
      // SnapshotRootIndex should be 3: {root, Testsnapshot, sub1, s2, file1}
      snapshot = getSnapshot(nodesInPath, "s2");
      assertSnapshot(nodesInPath, true, snapshot, 3);
  
      // Check the INode for file1 (snapshot file)
      assertINodeFile(inodes[inodes.length - 1], file1);
    }

    // Check the INodes for path /TestSnapshot/sub1/file1
    String[] names = INode.getPathNames(file1.toString());
    byte[][] components = INode.getPathComponents(names);
    INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
        components.length, false);
    INode[] inodes = nodesInPath.getINodes();
    // The length of inodes should be equal to components.length
    assertEquals(inodes.length, components.length);
    // The number of non-null elements should be components.length - 1 since
    // file1 has been deleted
    assertEquals(nodesInPath.getNumNonNull(), components.length - 1);
    // The returned nodesInPath should be non-snapshot
    assertSnapshot(nodesInPath, false, snapshot, -1);
    // The last INode should be null, and the one before should be associated
    // with sub1
    assertNull(inodes[components.length - 1]);
    assertEquals(inodes[components.length - 2].getFullPathName(),
        sub1.toString());
    assertEquals(inodes[components.length - 3].getFullPathName(),
        dir.toString());
  }
  
  static private Snapshot s4;

  /** 
   * Test {@link INodeDirectory#getExistingPathINodes(byte[][], int, boolean)} 
   * for snapshot file while adding a new file after snapshot.
   */
  @Test
  public void testSnapshotPathINodesWithAddedFile() throws Exception {
    // Create a snapshot for the dir, and check the inodes for the path
    // pointing to a snapshot file
    hdfs.allowSnapshot(sub1.toString());
    hdfs.createSnapshot(sub1, "s4");
    
    // Add a new file /TestSnapshot/sub1/file3
    final Path file3 = new Path(sub1, "file3");
    DFSTestUtil.createFile(hdfs, file3, 1024, REPLICATION, seed);
  
    {
      // Check the inodes for /TestSnapshot/sub1/.snapshot/s4/file3
      String snapshotPath = sub1.toString() + "/.snapshot/s4/file3";
      String[] names = INode.getPathNames(snapshotPath);
      byte[][] components = INode.getPathComponents(names);
      INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
          components.length, false);
      INode[] inodes = nodesInPath.getINodes();
      // Length of inodes should be (components.length - 1), since we will ignore
      // ".snapshot" 
      assertEquals(inodes.length, components.length - 1);
      // The number of non-null inodes should be components.length - 2, since
      // snapshot of file3 does not exist
      assertEquals(nodesInPath.getNumNonNull(), components.length - 2);
      s4 = getSnapshot(nodesInPath, "s4");

      // SnapshotRootIndex should still be 3: {root, Testsnapshot, sub1, s4, null}
      assertSnapshot(nodesInPath, true, s4, 3);
  
      // Check the last INode in inodes, which should be null
      assertNull(inodes[inodes.length - 1]);
    }

    // Check the inodes for /TestSnapshot/sub1/file3
    String[] names = INode.getPathNames(file3.toString());
    byte[][] components = INode.getPathComponents(names);
    INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
        components.length, false);
    INode[] inodes = nodesInPath.getINodes();
    // The number of inodes should be equal to components.length
    assertEquals(inodes.length, components.length);

    // The returned nodesInPath should be non-snapshot
    assertSnapshot(nodesInPath, false, s4, -1);

    // The last INode should be associated with file3
    assertEquals(inodes[components.length - 1].getFullPathName(),
        file3.toString());
    assertEquals(inodes[components.length - 2].getFullPathName(),
        sub1.toString());
    assertEquals(inodes[components.length - 3].getFullPathName(),
        dir.toString());
  }
  
  /** 
   * Test {@link INodeDirectory#getExistingPathINodes(byte[][], int, boolean)} 
   * for snapshot file while modifying file after snapshot.
   */
//  TODO: disable it temporarily since it uses append.
//  @Test
  public void testSnapshotPathINodesAfterModification() throws Exception {
    //file1 was deleted, create it again.
    DFSTestUtil.createFile(hdfs, file1, 1024, REPLICATION, seed);

    // First check the INode for /TestSnapshot/sub1/file1
    String[] names = INode.getPathNames(file1.toString());
    byte[][] components = INode.getPathComponents(names);
    INodesInPath nodesInPath = fsdir.rootDir.getExistingPathINodes(components,
        components.length, false);
    INode[] inodes = nodesInPath.getINodes();
    // The number of inodes should be equal to components.length
    assertEquals(inodes.length, components.length);
    assertSnapshot(nodesInPath, false, s4, -1);

    // The last INode should be associated with file1
    assertEquals(inodes[components.length - 1].getFullPathName(),
        file1.toString());
    
    // Create a snapshot for the dir, and check the inodes for the path
    // pointing to a snapshot file
    hdfs.allowSnapshot(sub1.toString());
    hdfs.createSnapshot(sub1, "s3");
    
    // Modify file1
    DFSTestUtil.appendFile(hdfs, file1, "the content for appending");

    // Check the INodes for snapshot of file1
    String snapshotPath = sub1.toString() + "/.snapshot/s3/file1";
    names = INode.getPathNames(snapshotPath);
    components = INode.getPathComponents(names);
    INodesInPath ssNodesInPath = fsdir.rootDir.getExistingPathINodes(
        components, components.length, false);
    INode[] ssInodes = ssNodesInPath.getINodes();
    // Length of ssInodes should be (components.length - 1), since we will
    // ignore ".snapshot" 
    assertEquals(ssInodes.length, components.length - 1);
    final Snapshot s3 = getSnapshot(ssNodesInPath, "s3");
    assertSnapshot(ssNodesInPath, true, s3, 3);
    // Check the INode for snapshot of file1
    INode snapshotFileNode = ssInodes[ssInodes.length - 1]; 
    assertEquals(snapshotFileNode.getLocalName(), file1.getName());
    assertTrue(snapshotFileNode instanceof INodeFileSnapshot);
    // The modification time of the snapshot INode should be the same with the
    // original INode before modification
    assertEquals(inodes[inodes.length - 1].getModificationTime(),
        ssInodes[ssInodes.length - 1].getModificationTime());

    // Check the INode for /TestSnapshot/sub1/file1 again
    names = INode.getPathNames(file1.toString());
    components = INode.getPathComponents(names);
    INodesInPath newNodesInPath = fsdir.rootDir
        .getExistingPathINodes(components, components.length, false);
    assertSnapshot(newNodesInPath, false, s3, -1);
    INode[] newInodes = newNodesInPath.getINodes();
    // The number of inodes should be equal to components.length
    assertEquals(newInodes.length, components.length);
    // The last INode should be associated with file1
    final int last = components.length - 1;
    assertEquals(newInodes[last].getFullPathName(), file1.toString());
    // The modification time of the INode for file3 should have been changed
    Assert.assertFalse(inodes[last].getModificationTime()
        == newInodes[last].getModificationTime());
  }
}
