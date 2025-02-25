/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.api.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Charsets;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang.StringUtils;
import org.apache.helix.ZNRecord;
import org.apache.helix.manager.zk.ZNRecordSerializer;
import org.apache.helix.util.GZipCompressionUtil;
import org.apache.pinot.controller.api.access.AccessType;
import org.apache.pinot.controller.api.access.Authenticate;
import org.apache.pinot.controller.api.exception.ControllerApplicationException;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.spi.utils.JsonUtils;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Api(tags = Constants.ZOOKEEPER)
@Path("/")
public class ZookeeperResource {

  public static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperResource.class);

  @Inject
  PinotHelixResourceManager _pinotHelixResourceManager;

  ZNRecordSerializer _znRecordSerializer = new ZNRecordSerializer();

  @GET
  @Path("/zk/get")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Get content of the znode")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "ZK Path not found"),
      @ApiResponse(code = 204, message = "No Content"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String getData(
      @ApiParam(value = "Zookeeper Path, must start with /", required = true) @QueryParam("path") String path) {

    path = validateAndNormalizeZKPath(path, true);

    ZNRecord znRecord = _pinotHelixResourceManager.readZKData(path);
    if (znRecord != null) {
      byte[] serializeBytes = _znRecordSerializer.serialize(znRecord);
      if (GZipCompressionUtil.isCompressed(serializeBytes)) {
        try {
          serializeBytes = GZipCompressionUtil.uncompress(new ByteArrayInputStream(serializeBytes));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      return new String(serializeBytes, StandardCharsets.UTF_8);
    }
    return null;
  }

  @DELETE
  @Path("/zk/delete")
  @Authenticate(AccessType.DELETE)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Delete the znode at this path")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "ZK Path not found"),
      @ApiResponse(code = 204, message = "No Content"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public SuccessResponse delete(
      @ApiParam(value = "Zookeeper Path, must start with /", required = true) @QueryParam("path") String path) {

    path = validateAndNormalizeZKPath(path, true);

    boolean success = _pinotHelixResourceManager.deleteZKPath(path);
    if (success) {
      return new SuccessResponse("Successfully deleted path: " + path);
    } else {
      throw new ControllerApplicationException(LOGGER, "Failed to delete path: " + path,
          Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  @PUT
  @Path("/zk/put")
  @Authenticate(AccessType.UPDATE)
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Update the content of the node")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "ZK Path not found"),
      @ApiResponse(code = 204, message = "No Content"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public SuccessResponse putData(
      @ApiParam(value = "Zookeeper Path, must start with /", required = true) @QueryParam("path") String path,
      @ApiParam(value = "Content") @QueryParam("data") @Nullable String data,
      @ApiParam(value = "expectedVersion", defaultValue = "-1") @QueryParam("expectedVersion") @DefaultValue("-1")
          int expectedVersion,
      @ApiParam(value = "accessOption", defaultValue = "1") @QueryParam("accessOption") @DefaultValue("1")
          int accessOption,
      @Nullable String payload) {

    path = validateAndNormalizeZKPath(path, false);

    if (StringUtils.isEmpty(data)) {
      data = payload;
    }
    if (StringUtils.isEmpty(data)) {
      throw new ControllerApplicationException(LOGGER, "Must provide data through query parameter or payload",
          Response.Status.BAD_REQUEST);
    }
    ZNRecord znRecord;
    try {
      znRecord = (ZNRecord) _znRecordSerializer.deserialize(data.getBytes(Charsets.UTF_8));
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, "Failed to deserialize the data", Response.Status.BAD_REQUEST,
          e);
    }
    try {
      boolean result = _pinotHelixResourceManager.setZKData(path, znRecord, expectedVersion, accessOption);
      if (result) {
        return new SuccessResponse("Successfully updated path: " + path);
      } else {
        throw new ControllerApplicationException(LOGGER, "Failed to update path: " + path,
            Response.Status.INTERNAL_SERVER_ERROR);
      }
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, "Failed to update path: " + path,
          Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @GET
  @Path("/zk/ls")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "List the child znodes")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "ZK Path not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String ls(
      @ApiParam(value = "Zookeeper Path, must start with /", required = true) @QueryParam("path") String path) {

    path = validateAndNormalizeZKPath(path, true);

    List<String> children = _pinotHelixResourceManager.getZKChildren(path);
    try {
      return JsonUtils.objectToString(children);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @GET
  @Path("/zk/lsl")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "List the child znodes along with Stats")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "ZK Path not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String lsl(
      @ApiParam(value = "Zookeeper Path, must start with /", required = true) @QueryParam("path") String path) {

    path = validateAndNormalizeZKPath(path, true);

    Map<String, Stat> childrenStats = _pinotHelixResourceManager.getZKChildrenStats(path);

    try {
      return JsonUtils.objectToString(childrenStats);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @GET
  @Path("/zk/stat")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Get the stat",
      notes = " Use this api to fetch additional details of a znode such as creation time, modified time, numChildren"
          + " etc ")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 404, message = "Table not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String stat(
      @ApiParam(value = "Zookeeper Path, must start with /", required = true) @QueryParam("path") String path) {

    path = validateAndNormalizeZKPath(path, true);

    Stat stat = _pinotHelixResourceManager.getZKStat(path);
    try {
      return JsonUtils.objectToString(stat);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private String validateAndNormalizeZKPath(String path, boolean shouldExist) {
    if (path == null) {
      throw new ControllerApplicationException(LOGGER, "ZKPath cannot be null", Response.Status.BAD_REQUEST);
    }
    path = path.trim();
    if (!path.startsWith("/")) {
      throw new ControllerApplicationException(LOGGER, "ZKPath " + path + " must start with /",
          Response.Status.BAD_REQUEST);
    }
    if (!path.equals("/") && path.endsWith("/")) {
      throw new ControllerApplicationException(LOGGER, "ZKPath " + path + " cannot end with /",
          Response.Status.BAD_REQUEST);
    }
    if (shouldExist && _pinotHelixResourceManager.getZKStat(path) == null) {
      throw new ControllerApplicationException(LOGGER, "ZKPath " + path + " does not exist", Response.Status.NOT_FOUND);
    }
    return path;
  }
}
