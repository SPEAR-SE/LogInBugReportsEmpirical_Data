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

package org.apache.hadoop.filecache;

import java.io.*;
import java.util.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapred.DefaultTaskController;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;

import java.net.URI;

/**
 * Distribute application-specific large, read-only files efficiently.
 * 
 * <p><code>DistributedCache</code> is a facility provided by the Map-Reduce
 * framework to cache files (text, archives, jars etc.) needed by applications.
 * </p>
 * 
 * <p>Applications specify the files, via urls (hdfs:// or http://) to be cached 
 * via the {@link org.apache.hadoop.mapred.JobConf}.
 * The <code>DistributedCache</code> assumes that the
 * files specified via hdfs:// urls are already present on the 
 * {@link FileSystem} at the path specified by the url.</p>
 * 
 * <p>The framework will copy the necessary files on to the slave node before 
 * any tasks for the job are executed on that node. Its efficiency stems from 
 * the fact that the files are only copied once per job and the ability to 
 * cache archives which are un-archived on the slaves.</p> 
 *
 * <p><code>DistributedCache</code> can be used to distribute simple, read-only
 * data/text files and/or more complex types such as archives, jars etc. 
 * Archives (zip, tar and tgz/tar.gz files) are un-archived at the slave nodes. 
 * Jars may be optionally added to the classpath of the tasks, a rudimentary 
 * software distribution mechanism.  Files have execution permissions.
 * Optionally users can also direct it to symlink the distributed cache file(s)
 * into the working directory of the task.</p>
 * 
 * <p><code>DistributedCache</code> tracks modification timestamps of the cache 
 * files. Clearly the cache files should not be modified by the application 
 * or externally while the job is executing.</p>
 * 
 * <p>Here is an illustrative example on how to use the 
 * <code>DistributedCache</code>:</p>
 * <p><blockquote><pre>
 *     // Setting up the cache for the application
 *     
 *     1. Copy the requisite files to the <code>FileSystem</code>:
 *     
 *     $ bin/hadoop fs -copyFromLocal lookup.dat /myapp/lookup.dat  
 *     $ bin/hadoop fs -copyFromLocal map.zip /myapp/map.zip  
 *     $ bin/hadoop fs -copyFromLocal mylib.jar /myapp/mylib.jar
 *     $ bin/hadoop fs -copyFromLocal mytar.tar /myapp/mytar.tar
 *     $ bin/hadoop fs -copyFromLocal mytgz.tgz /myapp/mytgz.tgz
 *     $ bin/hadoop fs -copyFromLocal mytargz.tar.gz /myapp/mytargz.tar.gz
 *     
 *     2. Setup the application's <code>JobConf</code>:
 *     
 *     JobConf job = new JobConf();
 *     DistributedCache.addCacheFile(new URI("/myapp/lookup.dat#lookup.dat"), 
 *                                   job);
 *     DistributedCache.addCacheArchive(new URI("/myapp/map.zip", job);
 *     DistributedCache.addFileToClassPath(new Path("/myapp/mylib.jar"), job);
 *     DistributedCache.addCacheArchive(new URI("/myapp/mytar.tar", job);
 *     DistributedCache.addCacheArchive(new URI("/myapp/mytgz.tgz", job);
 *     DistributedCache.addCacheArchive(new URI("/myapp/mytargz.tar.gz", job);
 *     
 *     3. Use the cached files in the {@link org.apache.hadoop.mapred.Mapper}
 *     or {@link org.apache.hadoop.mapred.Reducer}:
 *     
 *     public static class MapClass extends MapReduceBase  
 *     implements Mapper&lt;K, V, K, V&gt; {
 *     
 *       private Path[] localArchives;
 *       private Path[] localFiles;
 *       
 *       public void configure(JobConf job) {
 *         // Get the cached archives/files
 *         localArchives = DistributedCache.getLocalCacheArchives(job);
 *         localFiles = DistributedCache.getLocalCacheFiles(job);
 *       }
 *       
 *       public void map(K key, V value, 
 *                       OutputCollector&lt;K, V&gt; output, Reporter reporter) 
 *       throws IOException {
 *         // Use data from the cached archives/files here
 *         // ...
 *         // ...
 *         output.collect(k, v);
 *       }
 *     }
 *     
 * </pre></blockquote></p>
 * It is also very common to use the DistributedCache by using
 * {@link org.apache.hadoop.util.GenericOptionsParser}.
 *
 * This class includes methods that should be used by users
 * (specifically those mentioned in the example above, as well
 * as {@link DistributedCache#addArchiveToClassPath(Path, Configuration)}),
 * as well as methods intended for use by the MapReduce framework
 * (e.g., {@link org.apache.hadoop.mapred.JobClient}).  For implementation
 * details, see {@link TrackerDistributedCacheManager} and
 * {@link TaskDistributedCacheManager}.
 *
 * @see TrackerDistributedCacheManager
 * @see TaskDistributedCacheManager
 * @see org.apache.hadoop.mapred.JobConf
 * @see org.apache.hadoop.mapred.JobClient
 */
public class DistributedCache {
  /**
   * Warning: {@link #CACHE_FILES_SIZES} is not a *public* constant.
   */
  public static final String CACHE_FILES_SIZES = "mapred.cache.files.filesizes";
  
  /**
   * Warning: {@link #CACHE_ARCHIVES_SIZES} is not a *public* constant.
   */
  public static final String CACHE_ARCHIVES_SIZES = 
    "mapred.cache.archives.filesizes";

  /**
   * Warning: {@link #CACHE_ARCHIVES_TIMESTAMPS} is not a *public* constant.
   **/
  public static final String CACHE_ARCHIVES_TIMESTAMPS = "mapred.cache.archives.timestamps";

  /**
   * Warning: {@link #CACHE_FILES_TIMESTAMPS} is not a *public* constant.
   **/
  public static final String CACHE_FILES_TIMESTAMPS = "mapred.cache.files.timestamps";

  /**
   * Warning: {@link #CACHE_ARCHIVES} is not a *public* constant.
   **/
  public static final String CACHE_ARCHIVES = "mapred.cache.archives";

  /**
   * Warning: {@link #CACHE_FILES} is not a *public* constant.
   **/
  public static final String CACHE_FILES = "mapred.cache.files";

  /**
   * Warning: {@link #CACHE_LOCALARCHIVES} is not a *public* constant.
   **/
  public static final String CACHE_LOCALARCHIVES = "mapred.cache.localArchives";

  /**
   * Warning: {@link #CACHE_LOCALFILES} is not a *public* constant.
   **/
  public static final String CACHE_LOCALFILES = "mapred.cache.localFiles";

  /**
   * Warning: {@link #CACHE_SYMLINK} is not a *public* constant.
   **/
  public static final String CACHE_SYMLINK = "mapred.create.symlink";
  
  /**
   * Get the locally cached file or archive; it could either be 
   * previously cached (and valid) or copy it from the {@link FileSystem} now.
   * 
   * @param cache the cache to be localized, this should be specified as 
   * new URI(hdfs://hostname:port/absolute_path_to_file#LINKNAME). If no schema 
   * or hostname:port is provided the file is assumed to be in the filesystem
   * being used in the Configuration
   * @param conf The Confguration file which contains the filesystem
   * @param baseDir The base cache Dir where you wnat to localize the files/archives
   * @param fileStatus The file status on the dfs.
   * @param isArchive if the cache is an archive or a file. In case it is an
   *  archive with a .zip or .jar or .tar or .tgz or .tar.gz extension it will
   *  be unzipped/unjarred/untarred automatically 
   *  and the directory where the archive is unzipped/unjarred/untarred is
   *  returned as the Path.
   *  In case of a file, the path to the file is returned
   * @param confFileStamp this is the hdfs file modification timestamp to verify that the 
   * file to be cached hasn't changed since the job started
   * @param currentWorkDir this is the directory where you would want to create symlinks 
   * for the locally cached files/archives
   * @return the path to directory where the archives are unjarred in case of archives,
   * the path to the file where the file is copied locally 
   * @throws IOException
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  public static Path getLocalCache(URI cache, Configuration conf, 
                                   Path baseDir, FileStatus fileStatus,
                                   boolean isArchive, long confFileStamp,
                                   Path currentWorkDir) 
      throws IOException {
    return getLocalCache(cache, conf, baseDir, fileStatus, isArchive, 
        confFileStamp, currentWorkDir, true);
  }

  /**
   * Get the locally cached file or archive; it could either be 
   * previously cached (and valid) or copy it from the {@link FileSystem} now.
   * 
   * @param cache the cache to be localized, this should be specified as 
   * new URI(hdfs://hostname:port/absolute_path_to_file#LINKNAME). If no schema 
   * or hostname:port is provided the file is assumed to be in the filesystem
   * being used in the Configuration
   * @param conf The Confguration file which contains the filesystem
   * @param baseDir The base cache Dir where you wnat to localize the files/archives
   * @param fileStatus The file status on the dfs.
   * @param isArchive if the cache is an archive or a file. In case it is an
   *  archive with a .zip or .jar or .tar or .tgz or .tar.gz extension it will
   *  be unzipped/unjarred/untarred automatically 
   *  and the directory where the archive is unzipped/unjarred/untarred is
   *  returned as the Path.
   *  In case of a file, the path to the file is returned
   * @param confFileStamp this is the hdfs file modification timestamp to verify that the 
   * file to be cached hasn't changed since the job started
   * @param currentWorkDir this is the directory where you would want to create symlinks 
   * for the locally cached files/archives
   * @param honorSymLinkConf if this is false, then the symlinks are not
   * created even if conf says so (this is required for an optimization in task
   * launches
   * @return the path to directory where the archives are unjarred in case of archives,
   * the path to the file where the file is copied locally 
   * @throws IOException
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  public static Path getLocalCache(URI cache, Configuration conf, 
      Path baseDir, FileStatus fileStatus,
      boolean isArchive, long confFileStamp,
      Path currentWorkDir, boolean honorSymLinkConf) throws IOException {

    return new TrackerDistributedCacheManager(conf, new DefaultTaskController())
        .getLocalCache(cache, conf, baseDir.toString(), fileStatus, isArchive,
            confFileStamp, currentWorkDir, honorSymLinkConf, false);
  }

  /**
   * Get the locally cached file or archive; it could either be 
   * previously cached (and valid) or copy it from the {@link FileSystem} now.
   * 
   * @param cache the cache to be localized, this should be specified as 
   * new URI(hdfs://hostname:port/absolute_path_to_file#LINKNAME). If no schema 
   * or hostname:port is provided the file is assumed to be in the filesystem
   * being used in the Configuration
   * @param conf The Confguration file which contains the filesystem
   * @param baseDir The base cache Dir where you wnat to localize the files/archives
   * @param isArchive if the cache is an archive or a file. In case it is an 
   *  archive with a .zip or .jar or .tar or .tgz or .tar.gz extension it will 
   *  be unzipped/unjarred/untarred automatically 
   *  and the directory where the archive is unzipped/unjarred/untarred 
   *  is returned as the Path.
   *  In case of a file, the path to the file is returned
   * @param confFileStamp this is the hdfs file modification timestamp to verify that the 
   * file to be cached hasn't changed since the job started
   * @param currentWorkDir this is the directory where you would want to create symlinks 
   * for the locally cached files/archives
   * @return the path to directory where the archives are unjarred in case of archives,
   * the path to the file where the file is copied locally 
   * @throws IOException
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  public static Path getLocalCache(URI cache, Configuration conf, 
                                   Path baseDir, boolean isArchive,
                                   long confFileStamp, Path currentWorkDir) 
      throws IOException {
    return getLocalCache(cache, conf, 
                         baseDir, null, isArchive,
                         confFileStamp, currentWorkDir);
  }

  /**
   * This is the opposite of getlocalcache. When you are done with
   * using the cache, you need to release the cache
   * @param cache The cache URI to be released
   * @param conf configuration which contains the filesystem the cache 
   * is contained in.
   * @throws IOException
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  public static void releaseCache(URI cache, Configuration conf)
      throws IOException {
	// find the timestamp of the uri
    URI[] archives = DistributedCache.getCacheArchives(conf);
    URI[] files = DistributedCache.getCacheFiles(conf);
    String[] archivesTimestamps =
          DistributedCache.getArchiveTimestamps(conf);
    String[] filesTimestamps =
          DistributedCache.getFileTimestamps(conf);
    String timestamp = null;
    if (archives != null) {
      for (int i = 0; i < archives.length; i++) {
        if (archives[i].equals(cache)) {
          timestamp = archivesTimestamps[i];
          break;
        }
      }
    }
    if (timestamp == null && files != null) {
      for (int i = 0; i < files.length; i++) {
        if (files[i].equals(cache)) {
          timestamp = filesTimestamps[i];
          break;
        }
      }
    }
    if (timestamp == null) {
      throw new IOException("TimeStamp of the uri couldnot be found");
    }
    new TrackerDistributedCacheManager(conf, new DefaultTaskController())
        .releaseCache(cache, conf, Long.parseLong(timestamp));
  }
  
  /**
   * Returns the relative path of the dir this cache will be localized in
   * relative path that this cache will be localized in. For
   * hdfs://hostname:port/absolute_path -- the relative path is
   * hostname/absolute path -- if it is just /absolute_path -- then the
   * relative path is hostname of DFS this mapred cluster is running
   * on/absolute_path
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  @Deprecated
  public static String makeRelative(URI cache, Configuration conf)
      throws IOException {
    return new TrackerDistributedCacheManager(conf, new DefaultTaskController())
        .makeRelative(cache, conf);
  }

  /**
   * Returns {@link FileStatus} of a given cache file on hdfs.
   * @param conf configuration
   * @param cache cache file 
   * @return <code>FileStatus</code> of a given cache file on hdfs
   * @throws IOException
   */
  public static FileStatus getFileStatus(Configuration conf, URI cache)
    throws IOException {
    FileSystem fileSystem = FileSystem.get(cache, conf);
    Path filePath = new Path(cache.getPath());

    return fileSystem.getFileStatus(filePath);
  }
  
  /**
   * Returns mtime of a given cache file on hdfs.
   * @param conf configuration
   * @param cache cache file 
   * @return mtime of a given cache file on hdfs
   * @throws IOException
   */
  public static long getTimestamp(Configuration conf, URI cache)
    throws IOException {
    return getFileStatus(conf, cache).getModificationTime();
  }
  
  /**
   * This method create symlinks for all files in a given dir in another directory
   * @param conf the configuration
   * @param jobCacheDir the target directory for creating symlinks
   * @param workDir the directory in which the symlinks are created
   * @throws IOException
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  public static void createAllSymlink(Configuration conf, File jobCacheDir, File workDir)
    throws IOException{
    TrackerDistributedCacheManager.createAllSymlink(conf, jobCacheDir, workDir);
  }
  
  private static String getFileSysName(URI url) {
    String fsname = url.getScheme();
    if ("hdfs".equals(fsname)) {
      String host = url.getHost();
      int port = url.getPort();
      return (port == (-1)) ? host : (host + ":" + port);
    } else {
      return null;
    }
  }
  
  private static FileSystem getFileSystem(URI cache, Configuration conf)
    throws IOException {
    String fileSysName = getFileSysName(cache);
    if (fileSysName != null)
      return FileSystem.getNamed(fileSysName, conf);
    else
      return FileSystem.get(conf);
  }

  /**
   * Set the configuration with the given set of archives. Intended
   * to be used by user code.
   * @param archives The list of archives that need to be localized
   * @param conf Configuration which will be changed
   */
  public static void setCacheArchives(URI[] archives, Configuration conf) {
    String sarchives = StringUtils.uriToString(archives);
    conf.set(CACHE_ARCHIVES, sarchives);
  }

  /**
   * Set the configuration with the given set of files.  Intended to be
   * used by user code.
   * @param files The list of files that need to be localized
   * @param conf Configuration which will be changed
   */
  public static void setCacheFiles(URI[] files, Configuration conf) {
    String sfiles = StringUtils.uriToString(files);
    conf.set(CACHE_FILES, sfiles);
  }

  /**
   * Get cache archives set in the Configuration.  Used by
   * internal DistributedCache and MapReduce code.
   * @param conf The configuration which contains the archives
   * @return A URI array of the caches set in the Configuration
   * @throws IOException
   */
  public static URI[] getCacheArchives(Configuration conf) throws IOException {
    return StringUtils.stringToURI(conf.getStrings(CACHE_ARCHIVES));
  }

  /**
   * Get cache files set in the Configuration.  Used by internal
   * DistributedCache and MapReduce code.
   * @param conf The configuration which contains the files
   * @return A URI array of the files set in the Configuration
   * @throws IOException
   */
  public static URI[] getCacheFiles(Configuration conf) throws IOException {
    return StringUtils.stringToURI(conf.getStrings(CACHE_FILES));
  }

  /**
   * Return the path array of the localized caches.  Intended to be used
   * by user code.
   * @param conf Configuration that contains the localized archives
   * @return A path array of localized caches
   * @throws IOException
   */
  public static Path[] getLocalCacheArchives(Configuration conf)
    throws IOException {
    return StringUtils.stringToPath(conf
                                    .getStrings(CACHE_LOCALARCHIVES));
  }

  /**
   * Return the path array of the localized files.  Intended to be used
   * by user code.
   * @param conf Configuration that contains the localized files
   * @return A path array of localized files
   * @throws IOException
   */
  public static Path[] getLocalCacheFiles(Configuration conf)
    throws IOException {
    return StringUtils.stringToPath(conf.getStrings(CACHE_LOCALFILES));
  }

  /**
   * Get the timestamps of the archives.  Used by internal
   * DistributedCache and MapReduce code.
   * @param conf The configuration which stored the timestamps
   * @return a string array of timestamps 
   * @throws IOException
   */
  public static String[] getArchiveTimestamps(Configuration conf) {
    return conf.getStrings(CACHE_ARCHIVES_TIMESTAMPS);
  }


  /**
   * Get the timestamps of the files.  Used by internal
   * DistributedCache and MapReduce code.
   * @param conf The configuration which stored the timestamps
   * @return a string array of timestamps 
   * @throws IOException
   */
  public static String[] getFileTimestamps(Configuration conf) {
    return conf.getStrings(CACHE_FILES_TIMESTAMPS);
  }

  /**
   * This is to check the timestamp of the archives to be localized.
   * Used by internal MapReduce code.
   * @param conf Configuration which stores the timestamp's
   * @param timestamps comma separated list of timestamps of archives.
   * The order should be the same as the order in which the archives are added.
   */
  public static void setArchiveTimestamps(Configuration conf, String timestamps) {
    conf.set(CACHE_ARCHIVES_TIMESTAMPS, timestamps);
  }

  /**
   * This is to check the timestamp of the files to be localized.
   * Used by internal MapReduce code.
   * @param conf Configuration which stores the timestamp's
   * @param timestamps comma separated list of timestamps of files.
   * The order should be the same as the order in which the files are added.
   */
  public static void setFileTimestamps(Configuration conf, String timestamps) {
    conf.set(CACHE_FILES_TIMESTAMPS, timestamps);
  }
  
  /**
   * Set the conf to contain the location for localized archives.  Used
   * by internal DistributedCache code.
   * @param conf The conf to modify to contain the localized caches
   * @param str a comma separated list of local archives
   */
  public static void setLocalArchives(Configuration conf, String str) {
    conf.set(CACHE_LOCALARCHIVES, str);
  }

  /**
   * Set the conf to contain the location for localized files.  Used
   * by internal DistributedCache code.
   * @param conf The conf to modify to contain the localized caches
   * @param str a comma separated list of local files
   */
  public static void setLocalFiles(Configuration conf, String str) {
    conf.set(CACHE_LOCALFILES, str);
  }

  /**
   * Add a archives to be localized to the conf.  Intended to
   * be used by user code.
   * @param uri The uri of the cache to be localized
   * @param conf Configuration to add the cache to
   */
  public static void addCacheArchive(URI uri, Configuration conf) {
    String archives = conf.get(CACHE_ARCHIVES);
    conf.set(CACHE_ARCHIVES, archives == null ? uri.toString()
             : archives + "," + uri.toString());
  }
  
  /**
   * Add a file to be localized to the conf.  Intended
   * to be used by user code.
   * @param uri The uri of the cache to be localized
   * @param conf Configuration to add the cache to
   */
  public static void addCacheFile(URI uri, Configuration conf) {
    String files = conf.get(CACHE_FILES);
    conf.set(CACHE_FILES, files == null ? uri.toString() : files + ","
             + uri.toString());
  }

  /**
   * Add a file path to the current set of classpath entries. It adds the file
   * to cache as well.  Intended to be used by user code.
   * 
   * @param file Path of the file to be added
   * @param conf Configuration that contains the classpath setting
   */
  public static void addFileToClassPath(Path file, Configuration conf)
        throws IOException {
    String classpath = conf.get("mapred.job.classpath.files");
    conf.set("mapred.job.classpath.files", classpath == null ? file.toString()
             : classpath + System.getProperty("path.separator") + file.toString());
    FileSystem fs = FileSystem.get(conf);
    URI uri = fs.makeQualified(file).toUri();

    addCacheFile(uri, conf);
  }

  /**
   * Get the file entries in classpath as an array of Path.
   * Used by internal DistributedCache code.
   * 
   * @param conf Configuration that contains the classpath setting
   */
  public static Path[] getFileClassPaths(Configuration conf) {
    String classpath = conf.get("mapred.job.classpath.files");
    if (classpath == null)
      return null;
    ArrayList list = Collections.list(new StringTokenizer(classpath, System
                                                          .getProperty("path.separator")));
    Path[] paths = new Path[list.size()];
    for (int i = 0; i < list.size(); i++) {
      paths[i] = new Path((String) list.get(i));
    }
    return paths;
  }

  /**
   * Add an archive path to the current set of classpath entries. It adds the
   * archive to cache as well.  Intended to be used by user code.
   * 
   * @param archive Path of the archive to be added
   * @param conf Configuration that contains the classpath setting
   */
  public static void addArchiveToClassPath(Path archive, Configuration conf)
    throws IOException {
    String classpath = conf.get("mapred.job.classpath.archives");
    conf.set("mapred.job.classpath.archives", classpath == null ? archive
             .toString() : classpath + System.getProperty("path.separator")
             + archive.toString());
    FileSystem fs = FileSystem.get(conf);
    URI uri = fs.makeQualified(archive).toUri();

    addCacheArchive(uri, conf);
  }

  /**
   * Get the archive entries in classpath as an array of Path.
   * Used by internal DistributedCache code.
   * 
   * @param conf Configuration that contains the classpath setting
   */
  public static Path[] getArchiveClassPaths(Configuration conf) {
    String classpath = conf.get("mapred.job.classpath.archives");
    if (classpath == null)
      return null;
    ArrayList list = Collections.list(new StringTokenizer(classpath, System
                                                          .getProperty("path.separator")));
    Path[] paths = new Path[list.size()];
    for (int i = 0; i < list.size(); i++) {
      paths[i] = new Path((String) list.get(i));
    }
    return paths;
  }

  /**
   * This method allows you to create symlinks in the current working directory
   * of the task to all the cache files/archives.
   * Intended to be used by user code.
   * @param conf the jobconf 
   */
  public static void createSymlink(Configuration conf){
    conf.set(CACHE_SYMLINK, "yes");
  }
  
  /**
   * This method checks to see if symlinks are to be create for the 
   * localized cache files in the current working directory 
   * Used by internal DistributedCache code.
   * @param conf the jobconf
   * @return true if symlinks are to be created- else return false
   */
  public static boolean getSymlink(Configuration conf){
    String result = conf.get(CACHE_SYMLINK);
    if ("yes".equals(result)){
      return true;
    }
    return false;
  }

  /**
   * This method checks if there is a conflict in the fragment names 
   * of the uris. Also makes sure that each uri has a fragment. It 
   * is only to be called if you want to create symlinks for 
   * the various archives and files.  May be used by user code.
   * @param uriFiles The uri array of urifiles
   * @param uriArchives the uri array of uri archives
   */
  public static boolean checkURIs(URI[]  uriFiles, URI[] uriArchives){
    if ((uriFiles == null) && (uriArchives == null)){
      return true;
    }
    if (uriFiles != null){
      for (int i = 0; i < uriFiles.length; i++){
        String frag1 = uriFiles[i].getFragment();
        if (frag1 == null)
          return false;
        for (int j=i+1; j < uriFiles.length; j++){
          String frag2 = uriFiles[j].getFragment();
          if (frag2 == null)
            return false;
          if (frag1.equalsIgnoreCase(frag2))
            return false;
        }
        if (uriArchives != null){
          for (int j = 0; j < uriArchives.length; j++){
            String frag2 = uriArchives[j].getFragment();
            if (frag2 == null){
              return false;
            }
            if (frag1.equalsIgnoreCase(frag2))
              return false;
            for (int k=j+1; k < uriArchives.length; k++){
              String frag3 = uriArchives[k].getFragment();
              if (frag3 == null)
                return false;
              if (frag2.equalsIgnoreCase(frag3))
                return false;
            }
          }
        }
      }
    }
    return true;
  }

  /**
   * Clear the entire contents of the cache and delete the backing files. This
   * should only be used when the server is reinitializing, because the users
   * are going to lose their files.
   * @deprecated Internal to MapReduce framework.  Use DistributedCacheManager
   * instead.
   */
  public static void purgeCache(Configuration conf) throws IOException {
    new TrackerDistributedCacheManager(conf, new DefaultTaskController())
        .purgeCache();
  }
}