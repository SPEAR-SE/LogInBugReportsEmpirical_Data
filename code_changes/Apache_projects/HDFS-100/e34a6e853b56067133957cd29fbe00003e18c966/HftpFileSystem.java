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

package org.apache.hadoop.hdfs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.TimeZone;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.MD5MD5CRC32FileChecksum;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.server.common.JspHelper;
import org.apache.hadoop.hdfs.tools.DelegationTokenFetcher;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.Progressable;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;



/** An implementation of a protocol for accessing filesystems over HTTP.
 * The following implementation provides a limited, read-only interface
 * to a filesystem over HTTP.
 * @see org.apache.hadoop.hdfs.server.namenode.ListPathsServlet
 * @see org.apache.hadoop.hdfs.server.namenode.FileDataServlet
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class HftpFileSystem extends FileSystem {
  static {
    HttpURLConnection.setFollowRedirects(true);
  }

  protected InetSocketAddress nnAddr;
  protected UserGroupInformation ugi; 
  protected final Random ran = new Random();

  public static final String HFTP_TIMEZONE = "UTC";
  public static final String HFTP_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";
  private Token<? extends TokenIdentifier> delegationToken;
  public static final String HFTP_RENEWER = "fs.hftp.renewer";
  public static final String HFTP_SERVICE_NAME_KEY = "hdfs.service.host_";

  public static final SimpleDateFormat getDateFormat() {
    final SimpleDateFormat df = new SimpleDateFormat(HFTP_DATE_FORMAT);
    df.setTimeZone(TimeZone.getTimeZone(HFTP_TIMEZONE));
    return df;
  }

  protected static final ThreadLocal<SimpleDateFormat> df =
    new ThreadLocal<SimpleDateFormat>() {
      protected SimpleDateFormat initialValue() {
        return getDateFormat();
      }
    };

  @Override
  public void initialize(final URI name, final Configuration conf)
  throws IOException {
    super.initialize(name, conf);
    setConf(conf);
    this.ugi = UserGroupInformation.getCurrentUser(); 
    nnAddr = NetUtils.createSocketAddr(name.toString());
    
    if (UserGroupInformation.isSecurityEnabled()) {
      StringBuffer sb = new StringBuffer(HFTP_SERVICE_NAME_KEY);
      // configuration has the actual service name for this url. Build the key 
      // and get it.
      final String key = sb.append(NetUtils.normalizeHostName(name.getHost())).
      append(".").append(name.getPort()).toString();

      LOG.debug("Trying to find DT for " + name + " using key=" + key + "; conf=" + conf.get(key, ""));
      Text nnServiceNameText = new Text(conf.get(key, ""));

      Collection<Token<? extends TokenIdentifier>> tokens =
        ugi.getTokens();
      //try finding a token for this namenode (esp applicable for tasks
      //using hftp). If there exists one, just set the delegationField
      for (Token<? extends TokenIdentifier> t : tokens) {
        if ((t.getService()).equals(nnServiceNameText)) {
          LOG.debug("Found existing DT for " + name);
          delegationToken = t;
          return;
        }
      }
      //since we don't already have a token, go get one over https
      try {
        ugi.doAs(new PrivilegedExceptionAction<Object>() {
          public Object run() throws IOException {
            StringBuffer sb = new StringBuffer();
            //try https (on http we NEVER get a delegation token)
            String nnHttpUrl = "https://" + 
            (sb.append(NetUtils.normalizeHostName(name.getHost()))
                .append(":").append(conf.getInt("dfs.https.port", 50470))).
                toString();
            Credentials c;
            try {
              c = DelegationTokenFetcher.getDTfromRemote(nnHttpUrl, 
                  conf.get(HFTP_RENEWER));
            } catch (Exception e) {
              LOG.info("Couldn't get a delegation token from " + nnHttpUrl + 
              " using https.");
              //Maybe the server is in unsecure mode (that's bad but okay)
              return null;
            }
            for (Token<? extends TokenIdentifier> t : c.getAllTokens()) {
              //the service field is already set and so setService 
              //is not required
              delegationToken = t;
              LOG.debug("Got dt for " + getUri() + ";t.service="
                  +t.getService());
            }
            return null;
          }
        });
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }
  
  
  public Token<? extends TokenIdentifier> getDelegationToken() {
    return delegationToken;
  }

  @Override
  public URI getUri() {
    try {
      return new URI("hftp", null, nnAddr.getHostName(), nnAddr.getPort(),
                     null, null, null);
    } catch (URISyntaxException e) {
      return null;
    } 
  }


  /* 
    Construct URL pointing to file on namenode
  */
  URL getNamenodeFileURL(Path f) throws IOException {
    return getNamenodeURL("/data" + f.toUri().getPath(), "ugi=" + getUgiParameter());
  }

  /* 
    Construct URL pointing to namenode. 
  */
  URL getNamenodeURL(String path, String query) throws IOException {
    try {
      final URL url = new URI("http", null, nnAddr.getHostName(),
          nnAddr.getPort(), path, query, null).toURL();
      if (LOG.isTraceEnabled()) {
        LOG.trace("url=" + url);
      }
      return url;
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  /**
   * ugi parameter for http connection
   * 
   * @return user_shortname,group1,group2...
   */
  private String getUgiParameter() {
    StringBuilder ugiParamenter = new StringBuilder(ugi.getShortUserName());
    for(String g: ugi.getGroupNames()) {
      ugiParamenter.append(",");
      ugiParamenter.append(g);
    }
    return ugiParamenter.toString();
  }
  
  /**
   * Open an HTTP connection to the namenode to read file data and metadata.
   * @param path The path component of the URL
   * @param query The query component of the URL
   */
  protected HttpURLConnection openConnection(String path, String query)
      throws IOException {
    query = updateQuery(query);
    final URL url = getNamenodeURL(path, query);
    HttpURLConnection connection = (HttpURLConnection)url.openConnection();
    connection.setRequestMethod("GET");
    connection.connect();
    return connection;
  }

  protected String updateQuery(String query) throws IOException {
    String tokenString = null;
    if (UserGroupInformation.isSecurityEnabled()) {
      if (delegationToken != null) {
        tokenString = delegationToken.encodeToUrlString();
        return (query + JspHelper.SET_DELEGATION + tokenString);
      } // else we are talking to an unsecure cluster
    }
    return query;
  }

  @Override
  public FSDataInputStream open(Path f, int buffersize) throws IOException {
    URL u = getNamenodeURL("/data" + f.toUri().getPath(), 
        "ugi=" + getUgiParameter());
    return new FSDataInputStream(new ByteRangeInputStream(u));
  }

  /** Class to parse and store a listing reply from the server. */
  class LsParser extends DefaultHandler {

    ArrayList<FileStatus> fslist = new ArrayList<FileStatus>();

    public void startElement(String ns, String localname, String qname,
                Attributes attrs) throws SAXException {
      if ("listing".equals(qname)) return;
      if (!"file".equals(qname) && !"directory".equals(qname)) {
        if (RemoteException.class.getSimpleName().equals(qname)) {
          throw new SAXException(RemoteException.valueOf(attrs));
        }
        throw new SAXException("Unrecognized entry: " + qname);
      }
      long modif;
      long atime = 0;
      try {
        final SimpleDateFormat ldf = df.get();
        modif = ldf.parse(attrs.getValue("modified")).getTime();
        String astr = attrs.getValue("accesstime");
        if (astr != null) {
          atime = ldf.parse(astr).getTime();
        }
      } catch (ParseException e) { throw new SAXException(e); }
      FileStatus fs = "file".equals(qname)
        ? new FileStatus(
              Long.valueOf(attrs.getValue("size")).longValue(), false,
              Short.valueOf(attrs.getValue("replication")).shortValue(),
              Long.valueOf(attrs.getValue("blocksize")).longValue(),
              modif, atime, FsPermission.valueOf(attrs.getValue("permission")),
              attrs.getValue("owner"), attrs.getValue("group"),
              new Path(getUri().toString(), attrs.getValue("path"))
                .makeQualified(HftpFileSystem.this))
        : new FileStatus(0L, true, 0, 0L,
              modif, atime, FsPermission.valueOf(attrs.getValue("permission")),
              attrs.getValue("owner"), attrs.getValue("group"),
              new Path(getUri().toString(), attrs.getValue("path"))
                .makeQualified(HftpFileSystem.this));
      fslist.add(fs);
    }

    private void fetchList(String path, boolean recur) throws IOException {
      try {
        XMLReader xr = XMLReaderFactory.createXMLReader();
        xr.setContentHandler(this);
        HttpURLConnection connection = openConnection("/listPaths" + path,
            "ugi=" + getUgiParameter() + (recur? "&recursive=yes" : ""));

        InputStream resp = connection.getInputStream();
        xr.parse(new InputSource(resp));
      } catch(SAXException e) {
        final Exception embedded = e.getException();
        if (embedded != null && embedded instanceof IOException) {
          throw (IOException)embedded;
        }
        throw new IOException("invalid xml directory content", e);
      }
    }

    public FileStatus getFileStatus(Path f) throws IOException {
      fetchList(f.toUri().getPath(), false);
      if (fslist.size() == 0) {
        throw new FileNotFoundException("File does not exist: " + f);
      }
      return fslist.get(0);
    }

    public FileStatus[] listStatus(Path f, boolean recur) throws IOException {
      fetchList(f.toUri().getPath(), recur);
      if (fslist.size() > 0 && (fslist.size() != 1 || fslist.get(0).isDirectory())) {
        fslist.remove(0);
      }
      return fslist.toArray(new FileStatus[0]);
    }

    public FileStatus[] listStatus(Path f) throws IOException {
      return listStatus(f, false);
    }
  }

  @Override
  public FileStatus[] listStatus(Path f) throws IOException {
    LsParser lsparser = new LsParser();
    return lsparser.listStatus(f);
  }

  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    LsParser lsparser = new LsParser();
    return lsparser.getFileStatus(f);
  }

  private class ChecksumParser extends DefaultHandler {
    private FileChecksum filechecksum;

    /** {@inheritDoc} */
    public void startElement(String ns, String localname, String qname,
                Attributes attrs) throws SAXException {
      if (!MD5MD5CRC32FileChecksum.class.getName().equals(qname)) {
        if (RemoteException.class.getSimpleName().equals(qname)) {
          throw new SAXException(RemoteException.valueOf(attrs));
        }
        throw new SAXException("Unrecognized entry: " + qname);
      }

      filechecksum = MD5MD5CRC32FileChecksum.valueOf(attrs);
    }

    private FileChecksum getFileChecksum(String f) throws IOException {
      final HttpURLConnection connection = openConnection(
          "/fileChecksum" + f, "ugi=" + getUgiParameter());
      try {
        final XMLReader xr = XMLReaderFactory.createXMLReader();
        xr.setContentHandler(this);
        xr.parse(new InputSource(connection.getInputStream()));
      } catch(SAXException e) {
        final Exception embedded = e.getException();
        if (embedded != null && embedded instanceof IOException) {
          throw (IOException)embedded;
        }
        throw new IOException("invalid xml directory content", e);
      } finally {
        connection.disconnect();
      }
      return filechecksum;
    }
  }

  /** {@inheritDoc} */
  public FileChecksum getFileChecksum(Path f) throws IOException {
    final String s = makeQualified(f).toUri().getPath();
    return new ChecksumParser().getFileChecksum(s);
  }

  @Override
  public Path getWorkingDirectory() {
    return new Path("/").makeQualified(getUri(), null);
  }

  @Override
  public void setWorkingDirectory(Path f) { }

  /** This optional operation is not yet supported. */
  public FSDataOutputStream append(Path f, int bufferSize,
      Progressable progress) throws IOException {
    throw new IOException("Not supported");
  }

  @Override
  public FSDataOutputStream create(Path f, FsPermission permission,
      boolean overwrite, int bufferSize, short replication,
      long blockSize, Progressable progress) throws IOException {
    throw new IOException("Not supported");
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    throw new IOException("Not supported");
  }

  @Override 
  public boolean delete(Path f, boolean recursive) throws IOException {
    throw new IOException("Not supported");
  }
  
  @Override
  public boolean mkdirs(Path f, FsPermission permission) throws IOException {
    throw new IOException("Not supported");
  }

  /**
   * A parser for parsing {@link ContentSummary} xml.
   */
  private class ContentSummaryParser extends DefaultHandler {
    private ContentSummary contentsummary;

    /** {@inheritDoc} */
    public void startElement(String ns, String localname, String qname,
                Attributes attrs) throws SAXException {
      if (!ContentSummary.class.getName().equals(qname)) {
        if (RemoteException.class.getSimpleName().equals(qname)) {
          throw new SAXException(RemoteException.valueOf(attrs));
        }
        throw new SAXException("Unrecognized entry: " + qname);
      }

      contentsummary = toContentSummary(attrs);
    }

    /**
     * Connect to the name node and get content summary.  
     * @param path The path
     * @return The content summary for the path.
     * @throws IOException
     */
    private ContentSummary getContentSummary(String path) throws IOException {
      final HttpURLConnection connection = openConnection(
          "/contentSummary" + path, "ugi=" + getUgiParameter());
      InputStream in = null;
      try {
        in = connection.getInputStream();        

        final XMLReader xr = XMLReaderFactory.createXMLReader();
        xr.setContentHandler(this);
        xr.parse(new InputSource(in));
      } catch(FileNotFoundException fnfe) {
        //the server may not support getContentSummary
        return null;
      } catch(SAXException saxe) {
        final Exception embedded = saxe.getException();
        if (embedded != null && embedded instanceof IOException) {
          throw (IOException)embedded;
        }
        throw new IOException("Invalid xml format", saxe);
      } finally {
        if (in != null) {
          in.close();
        }
        connection.disconnect();
      }
      return contentsummary;
    }
  }

  /** Return the object represented in the attributes. */
  private static ContentSummary toContentSummary(Attributes attrs
      ) throws SAXException {
    final String length = attrs.getValue("length");
    final String fileCount = attrs.getValue("fileCount");
    final String directoryCount = attrs.getValue("directoryCount");
    final String quota = attrs.getValue("quota");
    final String spaceConsumed = attrs.getValue("spaceConsumed");
    final String spaceQuota = attrs.getValue("spaceQuota");

    if (length == null
        || fileCount == null
        || directoryCount == null
        || quota == null
        || spaceConsumed == null
        || spaceQuota == null) {
      return null;
    }

    try {
      return new ContentSummary(
          Long.parseLong(length),
          Long.parseLong(fileCount),
          Long.parseLong(directoryCount),
          Long.parseLong(quota),
          Long.parseLong(spaceConsumed),
          Long.parseLong(spaceQuota));
    } catch(Exception e) {
      throw new SAXException("Invalid attributes: length=" + length
          + ", fileCount=" + fileCount
          + ", directoryCount=" + directoryCount
          + ", quota=" + quota
          + ", spaceConsumed=" + spaceConsumed
          + ", spaceQuota=" + spaceQuota, e);
    }
  }

  /** {@inheritDoc} */
  public ContentSummary getContentSummary(Path f) throws IOException {
    final String s = makeQualified(f).toUri().getPath();
    final ContentSummary cs = new ContentSummaryParser().getContentSummary(s);
    return cs != null? cs: super.getContentSummary(f);
  }
}
