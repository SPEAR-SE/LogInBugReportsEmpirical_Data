/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfsproxy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.ArrayList;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.cactus.FilterTestCase;
import org.apache.cactus.WebRequest;
import org.apache.cactus.WebResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;

public class TestAuthorizationFilter extends FilterTestCase {

  public static final Log LOG = LogFactory.getLog(TestAuthorizationFilter.class);

  private class DummyFilterChain implements FilterChain {
    public void doFilter(ServletRequest theRequest, ServletResponse theResponse)
        throws IOException, ServletException {
      PrintWriter writer = theResponse.getWriter();

      writer.print("<p>some content</p>");
      writer.close();
    }

    public void init(FilterConfig theConfig) {
    }

    public void destroy() {
    }
  }

  public void beginPathRestriction(WebRequest theRequest) {
    theRequest.setURL("proxy-test:0", null, "/streamFile", null,
        "filename=/nontestdir");
  }

  public void testPathRestriction() throws ServletException, IOException {
    AuthorizationFilter filter = new AuthorizationFilter();
    request.setRemoteIPAddress("127.0.0.1");
    request.setAttribute("org.apache.hadoop.hdfsproxy.authorized.userID",
        System.getProperty("user.name"));
    List<Path> paths = new ArrayList<Path>();
    paths.add(new Path("/deny"));
    request.setAttribute("org.apache.hadoop.hdfsproxy.authorized.paths",
        paths);
    FilterChain mockFilterChain = new DummyFilterChain();
    filter.doFilter(request, response, mockFilterChain);
  }

  public void endPathRestriction(WebResponse theResponse) {
    assertEquals(theResponse.getStatusCode(), 403);
    assertTrue("Text missing 'User not authorized to access path' : : ["
        + theResponse.getText() + "]", theResponse.getText().indexOf(
        "is not authorized to access path") > 0);
  }
}
