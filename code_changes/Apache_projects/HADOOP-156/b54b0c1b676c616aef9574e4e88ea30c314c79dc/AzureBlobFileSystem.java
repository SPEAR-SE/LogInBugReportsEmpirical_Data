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

package org.apache.hadoop.fs.azurebfs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.hadoop.fs.azurebfs.services.AbfsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIOException;
import org.apache.hadoop.fs.azurebfs.constants.FileSystemConfigurations;
import org.apache.hadoop.fs.azurebfs.constants.FileSystemUriSchemes;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AbfsRestOperationException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AzureBlobFileSystemException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.FileSystemOperationUnhandledException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidUriAuthorityException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidUriException;
import org.apache.hadoop.fs.azurebfs.contracts.services.AzureServiceErrorCode;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Progressable;

/**
 * A {@link org.apache.hadoop.fs.FileSystem} for reading and writing files stored on <a
 * href="http://store.azure.com/">Windows Azure</a>
 */
@InterfaceStability.Evolving
public class AzureBlobFileSystem extends FileSystem {
  public static final Logger LOG = LoggerFactory.getLogger(AzureBlobFileSystem.class);
  private URI uri;
  private Path workingDir;
  private UserGroupInformation userGroupInformation;
  private String user;
  private String primaryUserGroup;
  private AzureBlobFileSystemStore abfsStore;
  private boolean isClosed;

  @Override
  public void initialize(URI uri, Configuration configuration)
      throws IOException {
    uri = ensureAuthority(uri, configuration);
    super.initialize(uri, configuration);
    setConf(configuration);

    LOG.debug("Initializing AzureBlobFileSystem for {}", uri);

    this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());
    this.userGroupInformation = UserGroupInformation.getCurrentUser();
    this.user = userGroupInformation.getUserName();
    this.abfsStore = new AzureBlobFileSystemStore(uri, this.isSecure(), configuration, userGroupInformation);

    LOG.debug("Initializing NativeAzureFileSystem for {}", uri);

    this.setWorkingDirectory(this.getHomeDirectory());

    if (abfsStore.getAbfsConfiguration().getCreateRemoteFileSystemDuringInitialization()) {
      if (!this.fileSystemExists()) {
        this.createFileSystem();
      }
    }

    if (!abfsStore.getAbfsConfiguration().getSkipUserGroupMetadataDuringInitialization()) {
      this.primaryUserGroup = userGroupInformation.getPrimaryGroupName();
    } else {
      //Provide a default group name
      this.primaryUserGroup = this.user;
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(
        "AzureBlobFileSystem{");
    sb.append("uri=").append(uri);
    sb.append(", user='").append(user).append('\'');
    sb.append(", primaryUserGroup='").append(primaryUserGroup).append('\'');
    sb.append('}');
    return sb.toString();
  }

  public boolean isSecure() {
    return false;
  }

  @Override
  public URI getUri() {
    return this.uri;
  }

  @Override
  public FSDataInputStream open(final Path path, final int bufferSize) throws IOException {
    LOG.debug("AzureBlobFileSystem.open path: {} bufferSize: {}", path, bufferSize);

    try {
      InputStream inputStream = abfsStore.openFileForRead(makeQualified(path), statistics);
      return new FSDataInputStream(inputStream);
    } catch(AzureBlobFileSystemException ex) {
      checkException(path, ex);
      return null;
    }
  }

  @Override
  public FSDataOutputStream create(final Path f, final FsPermission permission, final boolean overwrite, final int bufferSize,
      final short replication, final long blockSize, final Progressable progress) throws IOException {
    LOG.debug("AzureBlobFileSystem.create path: {} permission: {} overwrite: {} bufferSize: {}",
        f,
        permission,
        overwrite,
        blockSize);

    try {
      OutputStream outputStream = abfsStore.createFile(makeQualified(f), overwrite);
      return new FSDataOutputStream(outputStream, statistics);
    } catch(AzureBlobFileSystemException ex) {
      checkException(f, ex);
      return null;
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public FSDataOutputStream createNonRecursive(final Path f, final FsPermission permission,
      final boolean overwrite, final int bufferSize, final short replication, final long blockSize,
      final Progressable progress) throws IOException {

    final Path parent = f.getParent();
    final FileStatus parentFileStatus = tryGetFileStatus(parent);

    if (parentFileStatus == null) {
      throw new FileNotFoundException("Cannot create file "
          + f.getName() + " because parent folder does not exist.");
    }

    return create(f, permission, overwrite, bufferSize, replication, blockSize, progress);
  }

  @Override
  @SuppressWarnings("deprecation")
  public FSDataOutputStream createNonRecursive(final Path f, final FsPermission permission,
      final EnumSet<CreateFlag> flags, final int bufferSize, final short replication, final long blockSize,
      final Progressable progress) throws IOException {

    // Check if file should be appended or overwritten. Assume that the file
    // is overwritten on if the CREATE and OVERWRITE create flags are set.
    final EnumSet<CreateFlag> createflags =
        EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE);
    final boolean overwrite = flags.containsAll(createflags);

    // Delegate the create non-recursive call.
    return this.createNonRecursive(f, permission, overwrite,
        bufferSize, replication, blockSize, progress);
  }

  @Override
  @SuppressWarnings("deprecation")
  public FSDataOutputStream createNonRecursive(final Path f,
      final boolean overwrite, final int bufferSize, final short replication, final long blockSize,
      final Progressable progress) throws IOException {
    return this.createNonRecursive(f, FsPermission.getFileDefault(),
        overwrite, bufferSize, replication, blockSize, progress);
  }

  @Override
  public FSDataOutputStream append(final Path f, final int bufferSize, final Progressable progress) throws IOException {
    LOG.debug(
        "AzureBlobFileSystem.append path: {} bufferSize: {}",
        f.toString(),
        bufferSize);

    try {
      OutputStream outputStream = abfsStore.openFileForWrite(makeQualified(f), false);
      return new FSDataOutputStream(outputStream, statistics);
    } catch(AzureBlobFileSystemException ex) {
      checkException(f, ex);
      return null;
    }
  }

  public boolean rename(final Path src, final Path dst) throws IOException {
    LOG.debug(
        "AzureBlobFileSystem.rename src: {} dst: {}", src.toString(), dst.toString());

    Path parentFolder = src.getParent();
    if (parentFolder == null) {
      return false;
    }

    final FileStatus dstFileStatus = tryGetFileStatus(dst);
    try {
      String sourceFileName = src.getName();
      Path adjustedDst = dst;

      if (dstFileStatus != null) {
        if (!dstFileStatus.isDirectory()) {
          return src.equals(dst);
        }

        adjustedDst = new Path(dst, sourceFileName);
      }

      abfsStore.rename(makeQualified(src), makeQualified(adjustedDst));
      return true;
    } catch(AzureBlobFileSystemException ex) {
      checkException(
              src,
              ex,
              AzureServiceErrorCode.PATH_ALREADY_EXISTS,
              AzureServiceErrorCode.INVALID_RENAME_SOURCE_PATH,
              AzureServiceErrorCode.SOURCE_PATH_NOT_FOUND,
              AzureServiceErrorCode.INVALID_SOURCE_OR_DESTINATION_RESOURCE_TYPE,
              AzureServiceErrorCode.RENAME_DESTINATION_PARENT_PATH_NOT_FOUND);
      return false;
    }

  }

  @Override
  public boolean delete(final Path f, final boolean recursive) throws IOException {
    LOG.debug(
        "AzureBlobFileSystem.delete path: {} recursive: {}", f.toString(), recursive);

    if (f.isRoot()) {
      if (!recursive) {
        return false;
      }

      return deleteRoot();
    }

    try {
      abfsStore.delete(makeQualified(f), recursive);
      return true;
    } catch (AzureBlobFileSystemException ex) {
      checkException(f, ex, AzureServiceErrorCode.PATH_NOT_FOUND);
      return false;
    }

  }

  @Override
  public FileStatus[] listStatus(final Path f) throws IOException {
    LOG.debug(
        "AzureBlobFileSystem.listStatus path: {}", f.toString());

    try {
      FileStatus[] result = abfsStore.listStatus(makeQualified(f));
      return result;
    } catch (AzureBlobFileSystemException ex) {
      checkException(f, ex);
      return null;
    }
  }

  @Override
  public boolean mkdirs(final Path f, final FsPermission permission) throws IOException {
    LOG.debug(
        "AzureBlobFileSystem.mkdirs path: {} permissions: {}", f, permission);

    final Path parentFolder = f.getParent();
    if (parentFolder == null) {
      // Cannot create root
      return true;
    }

    try {
      abfsStore.createDirectory(makeQualified(f));
      return true;
    } catch (AzureBlobFileSystemException ex) {
      checkException(f, ex, AzureServiceErrorCode.PATH_ALREADY_EXISTS);
      return true;
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (isClosed) {
      return;
    }

    super.close();
    LOG.debug("AzureBlobFileSystem.close");
    this.isClosed = true;
  }

  @Override
  public FileStatus getFileStatus(final Path f) throws IOException {
    LOG.debug("AzureBlobFileSystem.getFileStatus path: {}", f);

    try {
      return abfsStore.getFileStatus(makeQualified(f));
    } catch(AzureBlobFileSystemException ex) {
      checkException(f, ex);
      return null;
    }
  }

  @Override
  public Path getWorkingDirectory() {
    return this.workingDir;
  }

  @Override
  public void setWorkingDirectory(final Path newDir) {
    if (newDir.isAbsolute()) {
      this.workingDir = newDir;
    } else {
      this.workingDir = new Path(workingDir, newDir);
    }
  }

  @Override
  public String getScheme() {
    return FileSystemUriSchemes.ABFS_SCHEME;
  }

  @Override
  public Path getHomeDirectory() {
    return makeQualified(new Path(
            FileSystemConfigurations.USER_HOME_DIRECTORY_PREFIX
                + "/" + this.userGroupInformation.getShortUserName()));
  }

  /**
   * Return an array containing hostnames, offset and size of
   * portions of the given file. For ABFS we'll just lie and give
   * fake hosts to make sure we get many splits in MR jobs.
   */
  @Override
  public BlockLocation[] getFileBlockLocations(FileStatus file,
      long start, long len) {
    if (file == null) {
      return null;
    }

    if ((start < 0) || (len < 0)) {
      throw new IllegalArgumentException("Invalid start or len parameter");
    }

    if (file.getLen() < start) {
      return new BlockLocation[0];
    }
    final String blobLocationHost = abfsStore.getAbfsConfiguration().getAzureBlockLocationHost();

    final String[] name = { blobLocationHost };
    final String[] host = { blobLocationHost };
    long blockSize = file.getBlockSize();
    if (blockSize <= 0) {
      throw new IllegalArgumentException(
          "The block size for the given file is not a positive number: "
              + blockSize);
    }
    int numberOfLocations = (int) (len / blockSize)
        + ((len % blockSize == 0) ? 0 : 1);
    BlockLocation[] locations = new BlockLocation[numberOfLocations];
    for (int i = 0; i < locations.length; i++) {
      long currentOffset = start + (i * blockSize);
      long currentLength = Math.min(blockSize, start + len - currentOffset);
      locations[i] = new BlockLocation(name, host, currentOffset, currentLength);
    }

    return locations;
  }

  @Override
  protected void finalize() throws Throwable {
    LOG.debug("finalize() called.");
    close();
    super.finalize();
  }

  public String getOwnerUser() {
    return user;
  }

  public String getOwnerUserPrimaryGroup() {
    return primaryUserGroup;
  }

  private boolean deleteRoot() throws IOException {
    LOG.debug("Deleting root content");

    final ExecutorService executorService = Executors.newFixedThreadPool(10);

    try {
      final FileStatus[] ls = listStatus(makeQualified(new Path(File.separator)));
      final ArrayList<Future> deleteTasks = new ArrayList<>();
      for (final FileStatus fs : ls) {
        final Future deleteTask = executorService.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            delete(fs.getPath(), fs.isDirectory());
            return null;
          }
        });
        deleteTasks.add(deleteTask);
      }

      for (final Future deleteTask : deleteTasks) {
        execute("deleteRoot", new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            deleteTask.get();
            return null;
          }
        });
      }
    }
    finally {
      executorService.shutdownNow();
    }

    return true;
  }

  private FileStatus tryGetFileStatus(final Path f) {
    try {
      return getFileStatus(f);
    } catch (IOException ex) {
      LOG.debug("File not found {}", f);
      return null;
    }
  }

  private boolean fileSystemExists() throws IOException {
    LOG.debug(
            "AzureBlobFileSystem.fileSystemExists uri: {}", uri);
    try {
      abfsStore.getFilesystemProperties();
    } catch (AzureBlobFileSystemException ex) {
      try {
        checkException(null, ex);
        // Because HEAD request won't contain message body,
        // there is not way to get the storage error code
        // workaround here is to check its status code.
      } catch (FileNotFoundException e) {
        return false;
      }
    }
    return true;
  }

  private void createFileSystem() throws IOException {
    LOG.debug(
        "AzureBlobFileSystem.createFileSystem uri: {}", uri);
    try {
      abfsStore.createFilesystem();
    } catch (AzureBlobFileSystemException ex) {
      checkException(null, ex);
    }
  }

  private URI ensureAuthority(URI uri, final Configuration conf) {

    Preconditions.checkNotNull(uri, "uri");

    if (uri.getAuthority() == null) {
      final URI defaultUri = FileSystem.getDefaultUri(conf);

      if (defaultUri != null && isAbfsScheme(defaultUri.getScheme())) {
        try {
          // Reconstruct the URI with the authority from the default URI.
          uri = new URI(
              uri.getScheme(),
              defaultUri.getAuthority(),
              uri.getPath(),
              uri.getQuery(),
              uri.getFragment());
        } catch (URISyntaxException e) {
          // This should never happen.
          throw new IllegalArgumentException(new InvalidUriException(uri.toString()));
        }
      }
    }

    if (uri.getAuthority() == null) {
      throw new IllegalArgumentException(new InvalidUriAuthorityException(uri.toString()));
    }

    return uri;
  }

  private boolean isAbfsScheme(final String scheme) {
    if (scheme == null) {
      return false;
    }

    if (scheme.equals(FileSystemUriSchemes.ABFS_SCHEME)
        || scheme.equals(FileSystemUriSchemes.ABFS_SECURE_SCHEME)) {
      return true;
    }

    return false;
  }

  @VisibleForTesting
  <T> FileSystemOperation<T> execute(
      final String scopeDescription,
      final Callable<T> callableFileOperation) throws IOException {
    return execute(scopeDescription, callableFileOperation, null);
  }

  @VisibleForTesting
  <T> FileSystemOperation<T> execute(
      final String scopeDescription,
      final Callable<T> callableFileOperation,
      T defaultResultValue) throws IOException {

    try {
      final T executionResult = callableFileOperation.call();
      return new FileSystemOperation<>(executionResult, null);
    } catch (AbfsRestOperationException abfsRestOperationException) {
      return new FileSystemOperation<>(defaultResultValue, abfsRestOperationException);
    } catch (AzureBlobFileSystemException azureBlobFileSystemException) {
      throw new IOException(azureBlobFileSystemException);
    } catch (Exception exception) {
      if (exception instanceof ExecutionException) {
        exception = (Exception) getRootCause(exception);
      }
      final FileSystemOperationUnhandledException fileSystemOperationUnhandledException
          = new FileSystemOperationUnhandledException(exception);
      throw new IOException(fileSystemOperationUnhandledException);
    }
  }

  /**
   * Given a path and exception, choose which IOException subclass
   * to create.
   * Will return if and only iff the error code is in the list of allowed
   * error codes.
   * @param path path of operation triggering exception; may be null
   * @param exception the exception caught
   * @param allowedErrorCodesList varargs list of error codes.
   * @throws IOException if the exception error code is not on the allowed list.
   */
  private void checkException(final Path path,
                              final AzureBlobFileSystemException exception,
                              final AzureServiceErrorCode... allowedErrorCodesList) throws IOException {
    if (exception instanceof AbfsRestOperationException) {
      AbfsRestOperationException ere = (AbfsRestOperationException) exception;

      if (ArrayUtils.contains(allowedErrorCodesList, ere.getErrorCode())) {
        return;
      }
      int statusCode = ere.getStatusCode();

      //AbfsRestOperationException.getMessage() contains full error info including path/uri.
      if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
        throw (IOException) new FileNotFoundException(ere.getMessage())
            .initCause(exception);
      } else if (statusCode == HttpURLConnection.HTTP_CONFLICT) {
        throw (IOException) new FileAlreadyExistsException(ere.getMessage())
            .initCause(exception);
      } else {
        throw ere;
      }
    } else {
      if (path == null) {
        throw exception;
      }
      // record info of path
      throw new PathIOException(path.toString(), exception);
    }
  }

  /**
   * Gets the root cause of a provided {@link Throwable}.  If there is no cause for the
   * {@link Throwable} provided into this function, the original {@link Throwable} is returned.
   *
   * @param throwable starting {@link Throwable}
   * @return root cause {@link Throwable}
   */
  private Throwable getRootCause(Throwable throwable) {
    if (throwable == null) {
      throw new IllegalArgumentException("throwable can not be null");
    }

    Throwable result = throwable;
    while (result.getCause() != null) {
      result = result.getCause();
    }

    return result;
  }

  @VisibleForTesting
  FileSystem.Statistics getFsStatistics() {
    return this.statistics;
  }

  @VisibleForTesting
  static class FileSystemOperation<T> {
    private final T result;
    private final AbfsRestOperationException exception;

    FileSystemOperation(final T result, final AbfsRestOperationException exception) {
      this.result = result;
      this.exception = exception;
    }

    public boolean failed() {
      return this.exception != null;
    }
  }

  @VisibleForTesting
  AzureBlobFileSystemStore getAbfsStore() {
    return abfsStore;
  }

  @VisibleForTesting
  AbfsClient getAbfsClient() {
    return abfsStore.getClient();
  }
}
