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
package org.apache.hadoop.hdfs.tools;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang.WordUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveEntry;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveInfo;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveStats;
import org.apache.hadoop.hdfs.protocol.CachePoolEntry;
import org.apache.hadoop.hdfs.protocol.CachePoolInfo;
import org.apache.hadoop.hdfs.server.namenode.CachePool;
import org.apache.hadoop.hdfs.tools.TableListing.Justification;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;

import com.google.common.base.Joiner;

/**
 * This class implements command-line operations on the HDFS Cache.
 */
@InterfaceAudience.Private
public class CacheAdmin extends Configured implements Tool {

  /**
   * Maximum length for printed lines
   */
  private static final int MAX_LINE_WIDTH = 80;

  public CacheAdmin() {
    this(null);
  }

  public CacheAdmin(Configuration conf) {
    super(conf);
  }

  @Override
  public int run(String[] args) throws IOException {
    if (args.length == 0) {
      printUsage(false);
      return 1;
    }
    Command command = determineCommand(args[0]);
    if (command == null) {
      System.err.println("Can't understand command '" + args[0] + "'");
      if (!args[0].startsWith("-")) {
        System.err.println("Command names must start with dashes.");
      }
      printUsage(false);
      return 1;
    }
    List<String> argsList = new LinkedList<String>();
    for (int j = 1; j < args.length; j++) {
      argsList.add(args[j]);
    }
    return command.run(getConf(), argsList);
  }

  public static void main(String[] argsArray) throws IOException {
    CacheAdmin cacheAdmin = new CacheAdmin(new Configuration());
    System.exit(cacheAdmin.run(argsArray));
  }

  private static DistributedFileSystem getDFS(Configuration conf)
      throws IOException {
    FileSystem fs = FileSystem.get(conf);
    if (!(fs instanceof DistributedFileSystem)) {
      throw new IllegalArgumentException("FileSystem " + fs.getUri() + 
      " is not an HDFS file system");
    }
    return (DistributedFileSystem)fs;
  }

  /**
   * NN exceptions contain the stack trace as part of the exception message.
   * When it's a known error, pretty-print the error and squish the stack trace.
   */
  private static String prettifyException(Exception e) {
    return e.getClass().getSimpleName() + ": "
        + e.getLocalizedMessage().split("\n")[0];
  }

  private static TableListing getOptionDescriptionListing() {
    TableListing listing = new TableListing.Builder()
    .addField("").addField("", true)
    .wrapWidth(MAX_LINE_WIDTH).hideHeaders().build();
    return listing;
  }

  interface Command {
    String getName();
    String getShortUsage();
    String getLongUsage();
    int run(Configuration conf, List<String> args) throws IOException;
  }

  private static class AddCacheDirectiveInfoCommand implements Command {
    @Override
    public String getName() {
      return "-addDirective";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() +
          " -path <path> -pool <pool-name> " +
          "[-replication <replication>] [-ttl <time-to-live>]]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("<path>", "A path to cache. The path can be " +
          "a directory or a file.");
      listing.addRow("<pool-name>", "The pool to which the directive will be " +
          "added. You must have write permission on the cache pool "
          + "in order to add new directives.");
      listing.addRow("<replication>", "The cache replication factor to use. " +
          "Defaults to 1.");
      listing.addRow("<time-to-live>", "How long the directive is " +
          "valid. Can be specified in minutes, hours, and days via e.g. " +
          "30m, 4h, 2d. Valid units are [smhd]." +
          " If unspecified, the directive never expires.");
      return getShortUsage() + "\n" +
        "Add a new cache directive.\n\n" +
        listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      CacheDirectiveInfo.Builder builder = new CacheDirectiveInfo.Builder();

      String path = StringUtils.popOptionWithArgument("-path", args);
      if (path == null) {
        System.err.println("You must specify a path with -path.");
        return 1;
      }
      builder.setPath(new Path(path));

      String poolName = StringUtils.popOptionWithArgument("-pool", args);
      if (poolName == null) {
        System.err.println("You must specify a pool name with -pool.");
        return 1;
      }
      builder.setPool(poolName);

      String replicationString =
          StringUtils.popOptionWithArgument("-replication", args);
      if (replicationString != null) {
        Short replication = Short.parseShort(replicationString);
        builder.setReplication(replication);
      }

      String ttlString = StringUtils.popOptionWithArgument("-ttl", args);
      if (ttlString != null) {
        try {
          long ttl = DFSUtil.parseRelativeTime(ttlString);
          builder.setExpiration(CacheDirectiveInfo.Expiration.newRelative(ttl));
        } catch (IOException e) {
          System.err.println(
              "Error while parsing ttl value: " + e.getMessage());
          return 1;
        }
      }

      if (!args.isEmpty()) {
        System.err.println("Can't understand argument: " + args.get(0));
        return 1;
      }
        
      DistributedFileSystem dfs = getDFS(conf);
      CacheDirectiveInfo directive = builder.build();
      try {
        long id = dfs.addCacheDirective(directive);
        System.out.println("Added cache directive " + id);
      } catch (IOException e) {
        System.err.println(prettifyException(e));
        return 2;
      }

      return 0;
    }
  }

  private static class RemoveCacheDirectiveInfoCommand implements Command {
    @Override
    public String getName() {
      return "-removeDirective";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() + " <id>]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("<id>", "The id of the cache directive to remove.  " + 
        "You must have write permission on the pool of the " +
        "directive in order to remove it.  To see a list " +
        "of cache directive IDs, use the -listDirectives command.");
      return getShortUsage() + "\n" +
        "Remove a cache directive.\n\n" +
        listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      String idString= StringUtils.popFirstNonOption(args);
      if (idString == null) {
        System.err.println("You must specify a directive ID to remove.");
        return 1;
      }
      long id;
      try {
        id = Long.valueOf(idString);
      } catch (NumberFormatException e) {
        System.err.println("Invalid directive ID " + idString + ": expected " +
            "a numeric value.");
        return 1;
      }
      if (id <= 0) {
        System.err.println("Invalid directive ID " + id + ": ids must " +
            "be greater than 0.");
        return 1;
      }
      if (!args.isEmpty()) {
        System.err.println("Can't understand argument: " + args.get(0));
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      try {
        dfs.getClient().removeCacheDirective(id);
        System.out.println("Removed cached directive " + id);
      } catch (IOException e) {
        System.err.println(prettifyException(e));
        return 2;
      }
      return 0;
    }
  }

  private static class ModifyCacheDirectiveInfoCommand implements Command {
    @Override
    public String getName() {
      return "-modifyDirective";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() +
          " -id <id> [-path <path>] [-replication <replication>] " +
          "[-pool <pool-name>] [-ttl <time-to-live>]]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("<id>", "The ID of the directive to modify (required)");
      listing.addRow("<path>", "A path to cache. The path can be " +
          "a directory or a file. (optional)");
      listing.addRow("<replication>", "The cache replication factor to use. " +
          "(optional)");
      listing.addRow("<pool-name>", "The pool to which the directive will be " +
          "added. You must have write permission on the cache pool "
          + "in order to move a directive into it. (optional)");
      listing.addRow("<time-to-live>", "How long the directive is " +
          "valid. Can be specified in minutes, hours, and days via e.g. " +
          "30m, 4h, 2d. Valid units are [smhd]." +
          " If unspecified, the directive never expires.");
      return getShortUsage() + "\n" +
        "Modify a cache directive.\n\n" +
        listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      CacheDirectiveInfo.Builder builder =
        new CacheDirectiveInfo.Builder();
      boolean modified = false;
      String idString = StringUtils.popOptionWithArgument("-id", args);
      if (idString == null) {
        System.err.println("You must specify a directive ID with -id.");
        return 1;
      }
      builder.setId(Long.parseLong(idString));
      String path = StringUtils.popOptionWithArgument("-path", args);
      if (path != null) {
        builder.setPath(new Path(path));
        modified = true;
      }
      String replicationString =
        StringUtils.popOptionWithArgument("-replication", args);
      if (replicationString != null) {
        builder.setReplication(Short.parseShort(replicationString));
        modified = true;
      }
      String poolName =
        StringUtils.popOptionWithArgument("-pool", args);
      if (poolName != null) {
        builder.setPool(poolName);
        modified = true;
      }
      String ttlString = StringUtils.popOptionWithArgument("-ttl", args);
      if (ttlString != null) {
        long ttl;
        try {
          ttl = DFSUtil.parseRelativeTime(ttlString);
        } catch (IOException e) {
          System.err.println(
              "Error while parsing ttl value: " + e.getMessage());
          return 1;
        }
        builder.setExpiration(CacheDirectiveInfo.Expiration.newRelative(ttl));
        modified = true;
      }
      if (!args.isEmpty()) {
        System.err.println("Can't understand argument: " + args.get(0));
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      if (!modified) {
        System.err.println("No modifications were specified.");
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      try {
        dfs.modifyCacheDirective(builder.build());
        System.out.println("Modified cache directive " + idString);
      } catch (IOException e) {
        System.err.println(prettifyException(e));
        return 2;
      }
      return 0;
    }
  }

  private static class RemoveCacheDirectiveInfosCommand implements Command {
    @Override
    public String getName() {
      return "-removeDirectives";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() + " -path <path>]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("-path <path>", "The path of the cache directives to remove.  " +
        "You must have write permission on the pool of the directive in order " +
        "to remove it.  To see a list of cache directives, use the " +
        "-listDirectives command.");
      return getShortUsage() + "\n" +
        "Remove every cache directive with the specified path.\n\n" +
        listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      String path = StringUtils.popOptionWithArgument("-path", args);
      if (path == null) {
        System.err.println("You must specify a path with -path.");
        return 1;
      }
      if (!args.isEmpty()) {
        System.err.println("Can't understand argument: " + args.get(0));
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      RemoteIterator<CacheDirectiveEntry> iter =
          dfs.listCacheDirectives(
              new CacheDirectiveInfo.Builder().
                  setPath(new Path(path)).build());
      int exitCode = 0;
      while (iter.hasNext()) {
        CacheDirectiveEntry entry = iter.next();
        try {
          dfs.removeCacheDirective(entry.getInfo().getId());
          System.out.println("Removed cache directive " +
              entry.getInfo().getId());
        } catch (IOException e) {
          System.err.println(prettifyException(e));
          exitCode = 2;
        }
      }
      if (exitCode == 0) {
        System.out.println("Removed every cache directive with path " +
            path);
      }
      return exitCode;
    }
  }

  private static class ListCacheDirectiveInfoCommand implements Command {
    @Override
    public String getName() {
      return "-listDirectives";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() + " [-stats] [-path <path>] [-pool <pool>]]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("<path>", "List only " +
          "cache directives with this path. " +
          "Note that if there is a cache directive for <path> " +
          "in a cache pool that we don't have read access for, it " + 
          "will not be listed.");
      listing.addRow("<pool>", "List only path cache directives in that pool.");
      listing.addRow("-stats", "List path-based cache directive statistics.");
      return getShortUsage() + "\n" +
        "List cache directives.\n\n" +
        listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      CacheDirectiveInfo.Builder builder =
          new CacheDirectiveInfo.Builder();
      String pathFilter = StringUtils.popOptionWithArgument("-path", args);
      if (pathFilter != null) {
        builder.setPath(new Path(pathFilter));
      }
      String poolFilter = StringUtils.popOptionWithArgument("-pool", args);
      if (poolFilter != null) {
        builder.setPool(poolFilter);
      }
      boolean printStats = StringUtils.popOption("-stats", args);
      if (!args.isEmpty()) {
        System.err.println("Can't understand argument: " + args.get(0));
        return 1;
      }
      TableListing.Builder tableBuilder = new TableListing.Builder().
          addField("ID", Justification.RIGHT).
          addField("POOL", Justification.LEFT).
          addField("REPL", Justification.RIGHT).
          addField("EXPIRY", Justification.LEFT).
          addField("PATH", Justification.LEFT);
      if (printStats) {
        tableBuilder.addField("NEEDED", Justification.RIGHT).
                    addField("CACHED", Justification.RIGHT).
                    addField("FILES", Justification.RIGHT);
      }
      TableListing tableListing = tableBuilder.build();

      DistributedFileSystem dfs = getDFS(conf);
      RemoteIterator<CacheDirectiveEntry> iter =
          dfs.listCacheDirectives(builder.build());
      int numEntries = 0;
      while (iter.hasNext()) {
        CacheDirectiveEntry entry = iter.next();
        CacheDirectiveInfo directive = entry.getInfo();
        CacheDirectiveStats stats = entry.getStats();
        List<String> row = new LinkedList<String>();
        row.add("" + directive.getId());
        row.add(directive.getPool());
        row.add("" + directive.getReplication());
        String expiry;
        if (directive.getExpiration().getMillis() ==
            CacheDirectiveInfo.Expiration.EXPIRY_NEVER) {
          expiry = "never";
        } else {
          expiry = directive.getExpiration().toString();
        }
        row.add(expiry);
        row.add(directive.getPath().toUri().getPath());
        if (printStats) {
          row.add("" + stats.getBytesNeeded());
          row.add("" + stats.getBytesCached());
          row.add("" + stats.getFilesAffected());
        }
        tableListing.addRow(row.toArray(new String[0]));
        numEntries++;
      }
      System.out.print(String.format("Found %d entr%s\n",
          numEntries, numEntries == 1 ? "y" : "ies"));
      if (numEntries > 0) {
        System.out.print(tableListing);
      }
      return 0;
    }
  }

  private static class AddCachePoolCommand implements Command {

    private static final String NAME = "-addPool";

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public String getShortUsage() {
      return "[" + NAME + " <name> [-owner <owner>] " +
          "[-group <group>] [-mode <mode>] [-weight <weight>]]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();

      listing.addRow("<name>", "Name of the new pool.");
      listing.addRow("<owner>", "Username of the owner of the pool. " +
          "Defaults to the current user.");
      listing.addRow("<group>", "Group of the pool. " +
          "Defaults to the primary group name of the current user.");
      listing.addRow("<mode>", "UNIX-style permissions for the pool. " +
          "Permissions are specified in octal, e.g. 0755. " +
          "By default, this is set to " + String.format("0%03o",
          FsPermission.getCachePoolDefault().toShort()));
      listing.addRow("<weight>", "Weight of the pool. " +
          "This is a relative measure of the importance of the pool used " +
          "during cache resource management. By default, it is set to " +
          CachePool.DEFAULT_WEIGHT);

      return getShortUsage() + "\n" +
          "Add a new cache pool.\n\n" + 
          listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      String owner = StringUtils.popOptionWithArgument("-owner", args);
      if (owner == null) {
        owner = UserGroupInformation.getCurrentUser().getShortUserName();
      }
      String group = StringUtils.popOptionWithArgument("-group", args);
      if (group == null) {
        group = UserGroupInformation.getCurrentUser().getGroupNames()[0];
      }
      String modeString = StringUtils.popOptionWithArgument("-mode", args);
      int mode;
      if (modeString == null) {
        mode = FsPermission.getCachePoolDefault().toShort();
      } else {
        mode = Integer.parseInt(modeString, 8);
      }
      String weightString = StringUtils.popOptionWithArgument("-weight", args);
      int weight;
      if (weightString == null) {
        weight = CachePool.DEFAULT_WEIGHT;
      } else {
        weight = Integer.parseInt(weightString);
      }
      String name = StringUtils.popFirstNonOption(args);
      if (name == null) {
        System.err.println("You must specify a name when creating a " +
            "cache pool.");
        return 1;
      }
      if (!args.isEmpty()) {
        System.err.print("Can't understand arguments: " +
          Joiner.on(" ").join(args) + "\n");
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      CachePoolInfo info = new CachePoolInfo(name).
          setOwnerName(owner).
          setGroupName(group).
          setMode(new FsPermission((short)mode)).
          setWeight(weight);
      try {
        dfs.addCachePool(info);
      } catch (IOException e) {
        throw new RemoteException(e.getClass().getName(), e.getMessage());
      }
      System.out.println("Successfully added cache pool " + name + ".");
      return 0;
    }
  }

  private static class ModifyCachePoolCommand implements Command {

    @Override
    public String getName() {
      return "-modifyPool";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() + " <name> [-owner <owner>] " +
          "[-group <group>] [-mode <mode>] [-weight <weight>]]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();

      listing.addRow("<name>", "Name of the pool to modify.");
      listing.addRow("<owner>", "Username of the owner of the pool");
      listing.addRow("<group>", "Groupname of the group of the pool.");
      listing.addRow("<mode>", "Unix-style permissions of the pool in octal.");
      listing.addRow("<weight>", "Weight of the pool.");

      return getShortUsage() + "\n" +
          WordUtils.wrap("Modifies the metadata of an existing cache pool. " +
          "See usage of " + AddCachePoolCommand.NAME + " for more details",
          MAX_LINE_WIDTH) + "\n\n" +
          listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      String owner = StringUtils.popOptionWithArgument("-owner", args);
      String group = StringUtils.popOptionWithArgument("-group", args);
      String modeString = StringUtils.popOptionWithArgument("-mode", args);
      Integer mode = (modeString == null) ?
          null : Integer.parseInt(modeString, 8);
      String weightString = StringUtils.popOptionWithArgument("-weight", args);
      Integer weight = (weightString == null) ?
          null : Integer.parseInt(weightString);
      String name = StringUtils.popFirstNonOption(args);
      if (name == null) {
        System.err.println("You must specify a name when creating a " +
            "cache pool.");
        return 1;
      }
      if (!args.isEmpty()) {
        System.err.print("Can't understand arguments: " +
          Joiner.on(" ").join(args) + "\n");
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      boolean changed = false;
      CachePoolInfo info = new CachePoolInfo(name);
      if (owner != null) {
        info.setOwnerName(owner);
        changed = true;
      }
      if (group != null) {
        info.setGroupName(group);
        changed = true;
      }
      if (mode != null) {
        info.setMode(new FsPermission(mode.shortValue()));
        changed = true;
      }
      if (weight != null) {
        info.setWeight(weight);
        changed = true;
      }
      if (!changed) {
        System.err.println("You must specify at least one attribute to " +
            "change in the cache pool.");
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      try {
        dfs.modifyCachePool(info);
      } catch (IOException e) {
        throw new RemoteException(e.getClass().getName(), e.getMessage());
      }
      System.out.print("Successfully modified cache pool " + name);
      String prefix = " to have ";
      if (owner != null) {
        System.out.print(prefix + "owner name " + owner);
        prefix = " and ";
      }
      if (group != null) {
        System.out.print(prefix + "group name " + group);
        prefix = " and ";
      }
      if (mode != null) {
        System.out.print(prefix + "mode " + new FsPermission(mode.shortValue()));
        prefix = " and ";
      }
      if (weight != null) {
        System.out.print(prefix + "weight " + weight);
        prefix = " and ";
      }
      System.out.print("\n");
      return 0;
    }
  }

  private static class RemoveCachePoolCommand implements Command {

    @Override
    public String getName() {
      return "-removePool";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() + " <name>]\n";
    }

    @Override
    public String getLongUsage() {
      return getShortUsage() + "\n" +
          WordUtils.wrap("Remove a cache pool. This also uncaches paths " +
              "associated with the pool.\n\n", MAX_LINE_WIDTH) +
          "<name>  Name of the cache pool to remove.\n";
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      String name = StringUtils.popFirstNonOption(args);
      if (name == null) {
        System.err.println("You must specify a name when deleting a " +
            "cache pool.");
        return 1;
      }
      if (!args.isEmpty()) {
        System.err.print("Can't understand arguments: " +
          Joiner.on(" ").join(args) + "\n");
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      try {
        dfs.removeCachePool(name);
      } catch (IOException e) {
        throw new RemoteException(e.getClass().getName(), e.getMessage());
      }
      System.out.println("Successfully removed cache pool " + name + ".");
      return 0;
    }
  }

  private static class ListCachePoolsCommand implements Command {

    @Override
    public String getName() {
      return "-listPools";
    }

    @Override
    public String getShortUsage() {
      return "[" + getName() + " [name]]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("[name]", "If specified, list only the named cache pool.");

      return getShortUsage() + "\n" +
          WordUtils.wrap("Display information about one or more cache pools, " +
              "e.g. name, owner, group, permissions, etc.", MAX_LINE_WIDTH) +
          "\n\n" +
          listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      String name = StringUtils.popFirstNonOption(args);
      if (!args.isEmpty()) {
        System.err.print("Can't understand arguments: " +
          Joiner.on(" ").join(args) + "\n");
        System.err.println("Usage is " + getShortUsage());
        return 1;
      }
      DistributedFileSystem dfs = getDFS(conf);
      TableListing listing = new TableListing.Builder().
          addField("NAME", Justification.LEFT).
          addField("OWNER", Justification.LEFT).
          addField("GROUP", Justification.LEFT).
          addField("MODE", Justification.LEFT).
          addField("WEIGHT", Justification.RIGHT).
          build();
      int numResults = 0;
      try {
        RemoteIterator<CachePoolEntry> iter = dfs.listCachePools();
        while (iter.hasNext()) {
          CachePoolEntry entry = iter.next();
          CachePoolInfo info = entry.getInfo();
          String[] row = new String[5];
          if (name == null || info.getPoolName().equals(name)) {
            row[0] = info.getPoolName();
            row[1] = info.getOwnerName();
            row[2] = info.getGroupName();
            row[3] = info.getMode() != null ? info.getMode().toString() : null;
            row[4] =
                info.getWeight() != null ? info.getWeight().toString() : null;
            listing.addRow(row);
            ++numResults;
            if (name != null) {
              break;
            }
          }
        }
      } catch (IOException e) {
        throw new RemoteException(e.getClass().getName(), e.getMessage());
      }
      System.out.print(String.format("Found %d result%s.\n", numResults,
          (numResults == 1 ? "" : "s")));
      if (numResults > 0) { 
        System.out.print(listing);
      }
      // If there are no results, we return 1 (failure exit code);
      // otherwise we return 0 (success exit code).
      return (numResults == 0) ? 1 : 0;
    }
  }

  private static class HelpCommand implements Command {
    @Override
    public String getName() {
      return "-help";
    }

    @Override
    public String getShortUsage() {
      return "[-help <command-name>]\n";
    }

    @Override
    public String getLongUsage() {
      TableListing listing = getOptionDescriptionListing();
      listing.addRow("<command-name>", "The command for which to get " +
          "detailed help. If no command is specified, print detailed help for " +
          "all commands");
      return getShortUsage() + "\n" +
        "Get detailed help about a command.\n\n" +
        listing.toString();
    }

    @Override
    public int run(Configuration conf, List<String> args) throws IOException {
      if (args.size() == 0) {
        for (Command command : COMMANDS) {
          System.err.println(command.getLongUsage());
        }
        return 0;
      }
      if (args.size() != 1) {
        System.out.println("You must give exactly one argument to -help.");
        return 0;
      }
      String commandName = args.get(0);
      // prepend a dash to match against the command names
      Command command = determineCommand("-"+commandName);
      if (command == null) {
        System.err.print("Sorry, I don't know the command '" +
          commandName + "'.\n");
        System.err.print("Valid help command names are:\n");
        String separator = "";
        for (Command c : COMMANDS) {
          System.err.print(separator + c.getName().substring(1));
          separator = ", ";
        }
        System.err.print("\n");
        return 1;
      }
      System.err.print(command.getLongUsage());
      return 0;
    }
  }

  private static Command[] COMMANDS = {
    new AddCacheDirectiveInfoCommand(),
    new ModifyCacheDirectiveInfoCommand(),
    new ListCacheDirectiveInfoCommand(),
    new RemoveCacheDirectiveInfoCommand(),
    new RemoveCacheDirectiveInfosCommand(),
    new AddCachePoolCommand(),
    new ModifyCachePoolCommand(),
    new RemoveCachePoolCommand(),
    new ListCachePoolsCommand(),
    new HelpCommand(),
  };

  private static void printUsage(boolean longUsage) {
    System.err.println(
        "Usage: bin/hdfs cacheadmin [COMMAND]");
    for (Command command : COMMANDS) {
      if (longUsage) {
        System.err.print(command.getLongUsage());
      } else {
        System.err.print("          " + command.getShortUsage());
      }
    }
    System.err.println();
  }

  private static Command determineCommand(String commandName) {
    for (int i = 0; i < COMMANDS.length; i++) {
      if (COMMANDS[i].getName().equals(commandName)) {
        return COMMANDS[i];
      }
    }
    return null;
  }
}
