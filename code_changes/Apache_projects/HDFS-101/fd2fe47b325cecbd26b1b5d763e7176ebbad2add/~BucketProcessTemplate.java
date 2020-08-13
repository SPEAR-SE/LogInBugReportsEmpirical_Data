/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.web.handlers;


import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.ozone.web.exceptions.ErrorTable;
import org.apache.hadoop.ozone.web.exceptions.OzoneException;
import org.apache.hadoop.ozone.web.headers.Header;
import org.apache.hadoop.ozone.web.interfaces.StorageHandler;
import org.apache.hadoop.ozone.web.interfaces.UserAuth;
import org.apache.hadoop.ozone.web.response.BucketInfo;
import org.apache.hadoop.ozone.web.response.ListKeys;
import org.apache.hadoop.ozone.web.utils.OzoneConsts;
import org.apache.hadoop.ozone.web.utils.OzoneUtils;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedList;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_OK;


/**
 * This class abstracts way the repetitive tasks in
 * Bucket handling code.
 */
public abstract class BucketProcessTemplate {

  /**
   * This function serves as the common error handling function
   * for all bucket related operations.
   *
   * @param volume - Volume Name
   * @param bucket - Bucket Name
   * @param request - Http Request
   * @param uriInfo - Http Uri
   * @param headers - Http Headers
   *
   * @return Response
   *
   * @throws OzoneException
   */
  public Response handleCall(String volume, String bucket, Request request,
                             UriInfo uriInfo, HttpHeaders headers)
      throws OzoneException {
    // TODO : Add logging
    String reqID = OzoneUtils.getRequestID();
    String hostName = OzoneUtils.getHostName();
    try {
      OzoneUtils.validate(request, headers, reqID, bucket, hostName);
      OzoneUtils.verifyBucketName(bucket);

      UserAuth auth = UserHandlerBuilder.getAuthHandler();
      UserArgs userArgs =
          new UserArgs(reqID, hostName, request, uriInfo, headers);
      userArgs.setUserName(auth.getUser(userArgs));

      BucketArgs args = new BucketArgs(volume, bucket, userArgs);
      return doProcess(args);
    } catch (IllegalArgumentException argExp) {
      OzoneException ex = ErrorTable
          .newError(ErrorTable.INVALID_BUCKET_NAME, reqID, bucket, hostName);
      ex.setMessage(argExp.getMessage());
      throw ex;
    } catch (IOException fsExp) {
      handleIOException(bucket, reqID, hostName, fsExp);
    }
    return null;
  }

  /**
   * Reads ACLs from headers and throws appropriate exception if needed.
   *
   * @param args - bucketArgs
   *
   * @throws OzoneException
   */
  void getAclsFromHeaders(BucketArgs args, boolean parseRemoveACL)
      throws OzoneException {
    try {
      List<String> acls = getAcls(args, Header.OZONE_ACL_REMOVE);
      if (acls != null && !acls.isEmpty()) {
        args.removeAcls(acls);
      }
      if ((!parseRemoveACL) && args.getRemoveAcls() != null) {
        OzoneException ex = ErrorTable.newError(ErrorTable.MALFORMED_ACL, args);
        ex.setMessage("Invalid Remove ACLs");
        throw ex;
      }

      acls = getAcls(args, Header.OZONE_ACL_ADD);
      if (acls != null && !acls.isEmpty()) {
        args.addAcls(acls);
      }
    } catch (IllegalArgumentException ex) {
      throw ErrorTable.newError(ErrorTable.MALFORMED_ACL, args, ex);
    }
  }

  /**
   * Converts FileSystem IO exceptions to OZONE exceptions.
   *
   * @param bucket Name of the bucket
   * @param reqID Request ID
   * @param hostName Machine Name
   * @param fsExp Exception
   *
   * @throws OzoneException
   */
  void handleIOException(String bucket, String reqID, String hostName,
                         IOException fsExp) throws OzoneException {

    if (fsExp instanceof FileAlreadyExistsException) {
      throw ErrorTable
          .newError(ErrorTable.BUCKET_ALREADY_EXISTS, reqID, bucket, hostName);
    }

    if (fsExp instanceof DirectoryNotEmptyException) {
      throw ErrorTable
          .newError(ErrorTable.BUCKET_NOT_EMPTY, reqID, bucket, hostName);
    }

    if (fsExp instanceof NoSuchFileException) {
      throw ErrorTable
          .newError(ErrorTable.INVALID_BUCKET_NAME, reqID, bucket, hostName);
    }

    // default we don't handle this exception yet.

    throw ErrorTable.newError(ErrorTable.SERVER_ERROR, reqID, bucket, hostName);
  }

  /**
   * Abstract function that gets implemented in the BucketHandler functions.
   * This function will just deal with the core file system related logic
   * and will rely on handleCall function for repetitive error checks
   *
   * @param args - parsed bucket args, name, userName, ACLs etc
   *
   * @return Response
   *
   * @throws OzoneException
   * @throws IOException
   */
  public abstract Response doProcess(BucketArgs args)
      throws OzoneException, IOException;


  /**
   * Returns the ACL String if available.
   * This function ignores all ACLs that are not prefixed with either
   * ADD or Remove
   *
   * @param args - BucketArgs
   * @param tag - Tag for different type of acls
   *
   * @return List of ACLs
   *
   */
  List<String> getAcls(BucketArgs args, String tag)  {
    List<String> aclStrings =
        args.getHeaders().getRequestHeader(Header.OZONE_ACLS);
    List<String> filteredSet = null;
    if (aclStrings != null) {
      filteredSet = new LinkedList<>();
      for (String s : aclStrings) {
        if (s.startsWith(tag)) {
          filteredSet.add(s.replaceFirst(tag, ""));
        }
      }
    }
    return filteredSet;
  }

  /**
   * Returns bucket versioning Info.
   *
   * @param args - BucketArgs
   *
   * @return - String
   *
   * @throws OzoneException
   */
  OzoneConsts.Versioning getVersioning(BucketArgs args) throws OzoneException {

    List<String> versionStrings =
        args.getHeaders().getRequestHeader(Header.OZONE_BUCKET_VERSIONING);
    if (versionStrings == null) {
      return null;
    }

    if (versionStrings.size() > 1) {
      OzoneException ex =
          ErrorTable.newError(ErrorTable.MALFORMED_BUCKET_VERSION, args);
      ex.setMessage("Exactly one bucket version header required");
      throw ex;
    }

    String version = versionStrings.get(0);
    try {
      return OzoneConsts.Versioning.valueOf(version);
    } catch (IllegalArgumentException ex) {
      throw ErrorTable.newError(ErrorTable.MALFORMED_BUCKET_VERSION, args, ex);
    }
  }


  /**
   * Returns Storage Class if Available or returns Default.
   *
   * @param args - bucketArgs
   *
   * @return StorageType
   *
   * @throws OzoneException
   */
  StorageType getStorageType(BucketArgs args) throws OzoneException {

    try {
      List<String> storageClassString =
          args.getHeaders().getRequestHeader(Header.OZONE_STORAGE_TYPE);
      if (storageClassString == null) {
        return null;
      }
      if (storageClassString.size() > 1) {
        OzoneException ex =
            ErrorTable.newError(ErrorTable.MALFORMED_STORAGE_TYPE, args);
        ex.setMessage("Exactly one storage class header required");
        throw ex;
      }
      return StorageType.valueOf(storageClassString.get(0).toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw ErrorTable.newError(ErrorTable.MALFORMED_STORAGE_TYPE, args, ex);
    }
  }

  /**
   * Returns BucketInfo response.
   *
   * @param args - BucketArgs
   *
   * @return BucketInfo
   *
   * @throws IOException
   * @throws OzoneException
   */
  Response getBucketInfoResponse(BucketArgs args)
      throws IOException, OzoneException {
    StorageHandler fs = StorageHandlerBuilder.getStorageHandler();
    BucketInfo info = fs.getBucketInfo(args);
    return OzoneUtils.getResponse(args, HTTP_OK, info.toJsonString());
  }

  /**
   * Returns list of objects in a bucket.
   * @param args - ListArgs
   * @return Response
   * @throws IOException
   * @throws OzoneException
   */
  Response getBucketKeysList(ListArgs args) throws IOException, OzoneException {
    StorageHandler fs = StorageHandlerBuilder.getStorageHandler();
    ListKeys objects = fs.listKeys(args);
    return OzoneUtils.getResponse(args, HTTP_OK, objects.toJsonString());
  }

}
