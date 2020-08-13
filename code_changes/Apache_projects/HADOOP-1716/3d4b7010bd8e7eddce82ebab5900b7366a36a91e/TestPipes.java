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

package org.apache.hadoop.mapred.pipes;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.MiniDFSCluster;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TestMiniMRWithDFS;
import org.apache.hadoop.util.StringUtils;

import junit.framework.TestCase;

public class TestPipes extends TestCase {
  private static final Log LOG =
    LogFactory.getLog(TestPipes.class.getName());

  public void testPipes() throws IOException {
    if (System.getProperty("compile.c++") == null) {
      LOG.info("compile.c++ is not defined, so skipping TestPipes");
      return;
    }
    MiniDFSCluster dfs = null;
    MiniMRCluster mr = null;
    FileSystem fs = null;
    Path cppExamples = new Path(System.getProperty("install.c++.examples"));
    Path inputPath = new Path("/testing/in");
    Path outputPath = new Path("/testing/out");
    try {
      final int numSlaves = 2;
      Configuration conf = new Configuration();
      dfs = new MiniDFSCluster(conf, numSlaves, true, null);
      fs = dfs.getFileSystem();
      mr = new MiniMRCluster(numSlaves, fs.getName(), 1);
      writeInputFile(fs, inputPath);
      runProgram(mr, fs, new Path(cppExamples, "bin/wordcount-simple"), 
                 inputPath, outputPath, twoSplitOutput);
      FileUtil.fullyDelete(fs, outputPath);
      assertFalse("output not cleaned up", fs.exists(outputPath));
      runProgram(mr, fs, new Path(cppExamples, "bin/wordcount-part"),
                 inputPath, outputPath, fixedPartitionOutput);
      runNonPipedProgram(mr, fs, new Path(cppExamples, "bin/wordcount-nopipe"));
      mr.waitUntilIdle();
    } finally {
      mr.shutdown();
      dfs.shutdown();
    }
  }

  final static String[] twoSplitOutput = new String[] {
    "`and\t1\na\t1\nand\t1\nbeginning\t1\nbook\t1\nbut\t1\nby\t1\n" +
    "conversation?'\t1\ndo:\t1\nhad\t2\nhaving\t1\nher\t2\nin\t1\nit\t1\n"+
    "it,\t1\nno\t1\nnothing\t1\nof\t3\non\t1\nonce\t1\nor\t3\npeeped\t1\n"+
    "pictures\t2\nthe\t3\nthought\t1\nto\t2\nuse\t1\nwas\t2\n",

    "Alice\t2\n`without\t1\nbank,\t1\nbook,'\t1\nconversations\t1\nget\t1\n" +
    "into\t1\nis\t1\nreading,\t1\nshe\t1\nsister\t2\nsitting\t1\ntired\t1\n" +
    "twice\t1\nvery\t1\nwhat\t1\n"
  };
  
  final static String[] fixedPartitionOutput = new String[] {
    "Alice\t2\n`and\t1\n`without\t1\na\t1\nand\t1\nbank,\t1\nbeginning\t1\n" +
    "book\t1\nbook,'\t1\nbut\t1\nby\t1\nconversation?'\t1\nconversations\t1\n"+
    "do:\t1\nget\t1\nhad\t2\nhaving\t1\nher\t2\nin\t1\ninto\t1\nis\t1\n" +
    "it\t1\nit,\t1\nno\t1\nnothing\t1\nof\t3\non\t1\nonce\t1\nor\t3\n" +
    "peeped\t1\npictures\t2\nreading,\t1\nshe\t1\nsister\t2\nsitting\t1\n" +
    "the\t3\nthought\t1\ntired\t1\nto\t2\ntwice\t1\nuse\t1\n" +
    "very\t1\nwas\t2\nwhat\t1\n",
    
    ""                                                   
  };
  
  private void writeInputFile(FileSystem fs, Path dir) throws IOException {
    DataOutputStream out = fs.create(new Path(dir, "part0"));
    out.writeBytes("Alice was beginning to get very tired of sitting by her\n");
    out.writeBytes("sister on the bank, and of having nothing to do: once\n");
    out.writeBytes("or twice she had peeped into the book her sister was\n");
    out.writeBytes("reading, but it had no pictures or conversations in\n");
    out.writeBytes("it, `and what is the use of a book,' thought Alice\n");
    out.writeBytes("`without pictures or conversation?'\n");
    out.close();
  }

  private void runProgram(MiniMRCluster mr, FileSystem fs, 
                          Path program, Path inputPath, Path outputPath,
                          String[] expectedResults
                         ) throws IOException {
    Path wordExec = new Path("/testing/bin/application");
    FileUtil.fullyDelete(fs, wordExec.getParent());
    fs.copyFromLocalFile(program, wordExec);                                         
    JobConf job = mr.createJobConf();
    job.setNumMapTasks(3);
    job.setNumReduceTasks(expectedResults.length);
    Submitter.setExecutable(job, fs.makeQualified(wordExec).toString());
    Submitter.setIsJavaRecordReader(job, true);
    Submitter.setIsJavaRecordWriter(job, true);
    job.setInputPath(inputPath);
    job.setOutputPath(outputPath);
    RunningJob result = Submitter.submitJob(job);
    assertTrue("pipes job failed", result.isSuccessful());
    List<String> results = new ArrayList<String>();
    for (Path p:fs.listPaths(outputPath)) {
      results.add(TestMiniMRWithDFS.readOutput(p, job));
    }
    assertEquals("number of reduces is wrong", 
                 expectedResults.length, results.size());
    for(int i=0; i < results.size(); i++) {
      assertEquals("pipes program " + program + " output " + i + " wrong",
                   expectedResults[i], results.get(i));
    }
  }
  
  /**
   * Run a map/reduce word count that does all of the map input and reduce
   * output directly rather than sending it back up to Java.
   * @param mr The mini mr cluster
   * @param dfs the dfs cluster
   * @param program the program to run
   * @throws IOException
   */
  private void runNonPipedProgram(MiniMRCluster mr, FileSystem dfs,
                                  Path program) throws IOException {
    JobConf job = mr.createJobConf();
    job.setInputFormat(WordCountInputFormat.class);
    FileSystem local = FileSystem.getLocal(job);
    Path testDir = new Path("file:" + System.getProperty("test.build.data"), 
                            "pipes");
    Path inDir = new Path(testDir, "input");
    Path outDir = new Path(testDir, "output");
    Path wordExec = new Path("/testing/bin/application");
    Path jobXml = new Path(testDir, "job.xml");
    FileUtil.fullyDelete(dfs, wordExec.getParent());
    dfs.copyFromLocalFile(program, wordExec);
    DataOutputStream out = local.create(new Path(inDir, "part0"));
    out.writeBytes("i am a silly test\n");
    out.writeBytes("you are silly\n");
    out.close();
    out = local.create(new Path(inDir, "part1"));
    out.writeBytes("all silly things drink java\n");
    out.close();
    FileUtil.fullyDelete(local, outDir);
    local.mkdirs(outDir);
    out = local.create(jobXml);
    job.write(out);
    out.close();
    try {
      Submitter.main(new String[]{"-conf", jobXml.toString(),
                                  "-input", inDir.toString(),
                                  "-output", outDir.toString(),
                                  "-program", 
                                  dfs.makeQualified(wordExec).toString(),
                                  "-reduces", "2"});
    } catch (Exception e) {
      assertTrue("got exception: " + StringUtils.stringifyException(e), false);
    }
  }
}
