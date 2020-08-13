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

package org.apache.hadoop.ozone.web.interfaces;

import org.apache.hadoop.ozone.web.exceptions.OzoneException;
import org.apache.hadoop.ozone.web.headers.Header;

import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

/**
 * Bucket Interface acts as the HTTP entry point for
 * bucket related functionality.
 */
@Path("/{volume}/{bucket}")
public interface Bucket {
  /**
   * createBucket call handles the POST request for Creating a Bucket.
   *
   * @param volume - Volume name
   * @param bucket - Bucket Name
   * @param req - Http request
   * @param info - Uri Info
   * @param headers - Http headers
   *
   * @return Response
   *
   * @throws OzoneException
   */
  @POST
  Response createBucket(@PathParam("volume") String volume,
                        @PathParam("bucket") String bucket,
                        @Context Request req, @Context UriInfo info,
                        @Context HttpHeaders headers) throws OzoneException;

  /**
   * updateBucket call handles the PUT request for updating a Bucket.
   *
   * @param volume - Volume name
   * @param bucket - Bucket name
   * @param req - Http request
   * @param info - Uri Info
   * @param headers - Http headers
   *
   * @return Response
   *
   * @throws OzoneException
   */
  @PUT
  Response updateBucket(@PathParam("volume") String volume,
                        @PathParam("bucket") String bucket,
                        @Context Request req, @Context UriInfo info,
                        @Context HttpHeaders headers) throws OzoneException;

  /**
   * Deletes an empty bucket.
   *
   * @param volume Volume name
   * @param bucket Bucket Name
   * @param req - Http request
   * @param info - Uri Info
   * @param headers - Http headers
   *
   * @return Response
   *
   * @throws OzoneException
   */
  @DELETE
  Response deleteBucket(@PathParam("volume") String volume,
                        @PathParam("bucket") String bucket,
                        @Context Request req, @Context UriInfo info,
                        @Context HttpHeaders headers) throws OzoneException;

  /**
   * List Buckets lists the contents of a bucket.
   *
   * @param volume - Storage Volume Name
   * @param bucket - Bucket Name
   * @param info - Information type needed
   * @param prefix - Prefix for the keys to be fetched
   * @param maxKeys - MaxNumber of Keys to Return
   * @param startPage - Continuation Token
   * @param req - Http request
   * @param headers - Http headers
   *
   * @return - Json Body
   *
   * @throws OzoneException
   */

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  Response listBucket(@PathParam("volume") String volume,
                      @PathParam("bucket") String bucket,
                      @DefaultValue(Header.OZONE_LIST_QUERY_KEY)
                      @QueryParam("info") String info,
                      @QueryParam("prefix") String prefix,
                      @DefaultValue("1000") @QueryParam("max-keys") int maxKeys,
                      @QueryParam("start-page") String startPage,
                      @Context Request req, @Context UriInfo uriInfo,
                      @Context HttpHeaders headers) throws OzoneException;


}
