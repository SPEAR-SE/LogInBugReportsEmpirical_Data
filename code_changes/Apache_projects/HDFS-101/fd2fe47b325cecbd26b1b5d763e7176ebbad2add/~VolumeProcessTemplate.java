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

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.ozone.web.exceptions.ErrorTable;
import org.apache.hadoop.ozone.web.exceptions.OzoneException;
import org.apache.hadoop.ozone.web.interfaces.StorageHandler;
import org.apache.hadoop.ozone.web.interfaces.UserAuth;
import org.apache.hadoop.ozone.web.response.ListBuckets;
import org.apache.hadoop.ozone.web.response.ListVolumes;
import org.apache.hadoop.ozone.web.response.VolumeInfo;
import org.apache.hadoop.ozone.web.utils.OzoneUtils;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;

import static java.net.HttpURLConnection.HTTP_OK;

/**
 * This class abstracts way the repetitive tasks in
 * handling volume related code.
 */
@InterfaceAudience.Private
public abstract class VolumeProcessTemplate {


  /**
   * The handle call is the common functionality for Volume
   * handling code.
   *
   * @param volume - Name of the Volume
   * @param request - request
   * @param info - UriInfo
   * @param headers - Http Headers
   *
   * @return Response
   *
   * @throws OzoneException
   */
  public Response handleCall(String volume, Request request, UriInfo info,
                             HttpHeaders headers) throws OzoneException {
    String reqID = OzoneUtils.getRequestID();
    String hostName = OzoneUtils.getHostName();
    try {

      OzoneUtils.validate(request, headers, reqID, volume, hostName);

      // we use the same logic for both bucket and volume names
      OzoneUtils.verifyBucketName(volume);
      UserAuth auth = UserHandlerBuilder.getAuthHandler();
      UserArgs userArgs = new UserArgs(reqID, hostName, request, info, headers);

      userArgs.setUserName(auth.getUser(userArgs));
      VolumeArgs args = new VolumeArgs(volume, userArgs);

      return doProcess(args);
    } catch (IllegalArgumentException ex) {
      OzoneException exp = ErrorTable
          .newError(ErrorTable.INVALID_VOLUME_NAME, reqID, volume, hostName);
      exp.setMessage(ex.getMessage());
      throw exp;
    } catch (IOException ex) {
      handleIOException(volume, reqID, hostName, ex);
    }
    return null;
  }

  /**
   * Specific handler for each call.
   *
   * @param args - Volume Args
   *
   * @return - Response
   *
   * @throws IOException
   * @throws OzoneException
   */
  public abstract Response doProcess(VolumeArgs args)
      throws IOException, OzoneException;

  /**
   * Maps Java File System Exceptions to Ozone Exceptions in the Volume path.
   *
   * @param volume - Name of the Volume
   * @param reqID - Request ID
   * @param hostName - HostName
   * @param fsExp - Exception
   *
   * @throws OzoneException
   */
  private void handleIOException(String volume, String reqID, String hostName,
                                 IOException fsExp) throws OzoneException {
    OzoneException exp = null;

    if (fsExp instanceof FileAlreadyExistsException) {
      exp = ErrorTable
          .newError(ErrorTable.VOLUME_ALREADY_EXISTS, reqID, volume, hostName);
    }

    if (fsExp instanceof DirectoryNotEmptyException) {
      exp = ErrorTable
          .newError(ErrorTable.VOLUME_NOT_EMPTY, reqID, volume, hostName);
    }

    if (fsExp instanceof NoSuchFileException) {
      exp = ErrorTable
          .newError(ErrorTable.INVALID_VOLUME_NAME, reqID, volume, hostName);
    }

    if ((fsExp != null) && (exp != null)) {
      exp.setMessage(fsExp.getMessage());
    }

    // We don't handle that FS error yet, report a Server Internal Error
    if (exp == null) {
      exp =
          ErrorTable.newError(ErrorTable.SERVER_ERROR, reqID, volume, hostName);
      if (fsExp != null) {
        exp.setMessage(fsExp.getMessage());
      }
    }
    throw exp;
  }

  /**
   * Set the user provided string into args and throw ozone exception
   * if needed.
   *
   * @param args - volume args
   * @param quota - quota sting
   *
   * @throws OzoneException
   */
  void setQuotaArgs(VolumeArgs args, String quota) throws OzoneException {
    try {
      args.setQuota(quota);
    } catch (IllegalArgumentException ex) {
      throw ErrorTable.newError(ErrorTable.MALFORMED_QUOTA, args, ex);
    }
  }

  /**
   * Wraps calls into volumeInfo data.
   *
   * @param args - volumeArgs
   *
   * @return - VolumeInfo
   *
   * @throws IOException
   * @throws OzoneException
   */
  Response getVolumeInfoResponse(VolumeArgs args)
      throws IOException, OzoneException {
    StorageHandler fs = StorageHandlerBuilder.getStorageHandler();
    VolumeInfo info = fs.getVolumeInfo(args);
    return OzoneUtils.getResponse(args, HTTP_OK, info.toJsonString());
  }


  /**
   * Returns all the volumes belonging to a user.
   *
   * @param user - userArgs
   *
   * @return - Response
   *
   * @throws OzoneException
   * @throws IOException
   */
  Response getVolumesByUser(UserArgs user) throws OzoneException, IOException {
    StorageHandler fs = StorageHandlerBuilder.getStorageHandler();
    ListVolumes volumes = fs.listVolumes(user);
    return OzoneUtils.getResponse(user, HTTP_OK, volumes.toJsonString());
  }


  /**
   * This call can also be invoked by Admins of the system where they can
   * get the list of buckets of any user.
   *
   * User makes a call like
   * GET / HTTP/1.1
   * Host: ozone.self
   *
   * @param args - volumeArgs
   *
   * @return Response - A list of buckets owned this user
   *
   * @throws OzoneException
   */
  Response getVolumesByUser(VolumeArgs args) throws OzoneException {
    String validatedUser = args.getUserName();
    try {
      UserAuth auth = UserHandlerBuilder.getAuthHandler();
      if (auth.isAdmin(args)) {
        validatedUser = auth.getOzoneUser(args);
        if (validatedUser == null) {
          validatedUser = auth.getUser(args);
        }
      }

      UserArgs user =
          new UserArgs(validatedUser, args.getRequestID(), args.getHostName(),
                       args.getRequest(), args.getUri(), args.getHeaders());
      return getVolumesByUser(user);
    } catch (IOException ex) {
      OzoneException exp = ErrorTable.newError(ErrorTable.SERVER_ERROR, args);
      exp.setMessage("unable to get the volume list for the user");
      throw exp;
    }
  }


  /**
   * Returns a list of Buckets in a Volume.
   *
   * @return List of Buckets
   *
   * @throws OzoneException
   */
  Response getBucketsInVolume(VolumeArgs args) throws OzoneException {
    String requestID = OzoneUtils.getRequestID();
    String hostName = OzoneUtils.getHostName();
    try {
      UserAuth auth = UserHandlerBuilder.getAuthHandler();
      // TODO : Check for ACLS access.
      StorageHandler fs = StorageHandlerBuilder.getStorageHandler();
      ListBuckets bucketList = fs.listBuckets(args);
      return OzoneUtils.getResponse(args, HTTP_OK, bucketList.toJsonString());
    } catch (IOException ex) {
      OzoneException exp =
          ErrorTable.newError(ErrorTable.SERVER_ERROR, requestID, "", hostName);
      exp.setMessage("unable to get the bucket list for the specified volume.");
      throw exp;

    }
  }
}
