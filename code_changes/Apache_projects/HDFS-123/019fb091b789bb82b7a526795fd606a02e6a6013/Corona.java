/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.tools;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Corona - A tool to populate ozone with data for testing.<br>
 * This is not a map-reduce program and this is not for benchmarking
 * Ozone write throughput.<br>
 * It supports both online and offline modes. Default mode is offline,
 * <i>-mode</i> can be used to change the mode.
 * <p>
 * In online mode, active internet connection is required,
 * common crawl data from AWS will be used.<br>
 * Default source is:<br>
 * https://commoncrawl.s3.amazonaws.com/crawl-data/
 * CC-MAIN-2017-17/warc.paths.gz<br>
 * (it contains the path to actual data segment)<br>
 * User can override this using <i>-source</i>.
 * The following values are derived from URL of Common Crawl data
 * <ul>
 * <li>Domain will be used as Volume</li>
 * <li>URL will be used as Bucket</li>
 * <li>FileName will be used as Key</li>
 * </ul></p>
 * In offline mode, the data will be random bytes and
 * size of data will be 10 KB.<br>
 * <ul>
 * <li>Default number of Volumes 10, <i>-numOfVolumes</i>
 * can be used to override</li>
 * <li>Default number of Buckets per Volume 1000, <i>-numOfBuckets</i>
 * can be used to override</li>
 * <li>Default number of Keys per Bucket 500000, <i>-numOfKeys</i>
 * can be used to override</li>
 * </ul>
 */
public final class Corona extends Configured implements Tool {

  private static final String HELP = "help";
  private static final String MODE = "mode";
  private static final String SOURCE = "source";
  private static final String VALIDATE_WRITE = "validateWrites";
  private static final String NUM_OF_THREADS = "numOfThreads";
  private static final String NUM_OF_VOLUMES = "numOfVolumes";
  private static final String NUM_OF_BUCKETS = "numOfBuckets";
  private static final String NUM_OF_KEYS = "numOfKeys";
  private static final String KEY_SIZE = "keySize";
  private static final String RATIS = "ratis";

  private static final String MODE_DEFAULT = "offline";
  private static final String SOURCE_DEFAULT =
      "https://commoncrawl.s3.amazonaws.com/" +
          "crawl-data/CC-MAIN-2017-17/warc.paths.gz";
  private static final String NUM_OF_THREADS_DEFAULT = "10";
  private static final String NUM_OF_VOLUMES_DEFAULT = "10";
  private static final String NUM_OF_BUCKETS_DEFAULT = "1000";
  private static final String NUM_OF_KEYS_DEFAULT = "500000";

  private static final int KEY_SIZE_DEFAULT = 10240;

  private static final Logger LOG =
      LoggerFactory.getLogger(Corona.class);

  private boolean printUsage = false;
  private boolean completed = false;
  private boolean exception = false;

  private String mode;
  private String source;
  private String numOfThreads;
  private String numOfVolumes;
  private String numOfBuckets;
  private String numOfKeys;
  private boolean useRatis;
  private int replicationFactor = 0;

  private int keySize;

  private boolean validateWrites;

  private OzoneClient ozoneClient;
  private ObjectStore objectStore;
  private ExecutorService processor;

  private long startTime;

  private AtomicLong volumeCreationTime;
  private AtomicLong bucketCreationTime;
  private AtomicLong keyCreationTime;
  private AtomicLong keyWriteTime;

  private AtomicLong totalBytesWritten;

  private AtomicInteger numberOfVolumesCreated;
  private AtomicInteger numberOfBucketsCreated;
  private AtomicLong numberOfKeysAdded;

  private Long totalWritesValidated;
  private Long writeValidationSuccessCount;
  private Long writeValidationFailureCount;

  private BlockingQueue<KeyValue> validationQueue;

  @VisibleForTesting
  Corona(Configuration conf) throws IOException {
    startTime = System.nanoTime();
    volumeCreationTime = new AtomicLong();
    bucketCreationTime = new AtomicLong();
    keyCreationTime = new AtomicLong();
    keyWriteTime = new AtomicLong();
    totalBytesWritten = new AtomicLong();
    numberOfVolumesCreated = new AtomicInteger();
    numberOfBucketsCreated = new AtomicInteger();
    numberOfKeysAdded = new AtomicLong();
    OzoneClientFactory.setConfiguration(conf);
    ozoneClient = OzoneClientFactory.getClient();
    objectStore = ozoneClient.getObjectStore();
  }

  /**
   * @param args arguments
   */
  public static void main(String[] args) throws Exception {
    Configuration conf = new OzoneConfiguration();
    int res = ToolRunner.run(conf, new Corona(conf), args);
    System.exit(res);
  }

  @Override
  public int run(String[] args) throws Exception {
    GenericOptionsParser parser = new GenericOptionsParser(getConf(),
        getOptions(), args);
    parseOptions(parser.getCommandLine());
    if (printUsage) {
      usage();
      return 0;
    }
    LOG.info("Number of Threads: " + numOfThreads);
    processor = Executors.newFixedThreadPool(Integer.parseInt(numOfThreads));
    addShutdownHook();
    if (mode.equals("online")) {
      LOG.info("Mode: online");
      throw new UnsupportedOperationException("Not yet implemented.");
    } else {
      LOG.info("Mode: offline");
      LOG.info("Number of Volumes: {}.", numOfVolumes);
      LOG.info("Number of Buckets per Volume: {}.", numOfBuckets);
      LOG.info("Number of Keys per Bucket: {}.", numOfKeys);
      LOG.info("Key size: {} bytes", keySize);
      for (int i = 0; i < Integer.parseInt(numOfVolumes); i++) {
        String volume = "vol-" + i + "-" +
            RandomStringUtils.randomNumeric(5);
        processor.submit(new OfflineProcessor(volume));
      }
    }
    Thread validator = null;
    if (validateWrites) {
      totalWritesValidated = 0L;
      writeValidationSuccessCount = 0L;
      writeValidationFailureCount = 0L;

      validationQueue =
          new ArrayBlockingQueue<>(Integer.parseInt(numOfThreads));
      validator = new Thread(new Validator());
      validator.start();
      LOG.info("Data validation is enabled.");
    }
    Thread progressbar = getProgressBarThread();
    LOG.info("Starting progress bar Thread.");
    progressbar.start();
    processor.shutdown();
    processor.awaitTermination(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
    completed = true;
    progressbar.join();
    if (validateWrites) {
      validator.join();
    }
    ozoneClient.close();
    return 0;
  }

  private Options getOptions() {
    Options options = new Options();

    OptionBuilder.withDescription("prints usage.");
    Option optHelp = OptionBuilder.create(HELP);

    OptionBuilder.withArgName("online | offline");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("specifies the mode of " +
        "Corona run.");
    Option optMode = OptionBuilder.create(MODE);

    OptionBuilder.withArgName("source url");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("specifies the URL of s3 " +
        "commoncrawl warc file to be used when the mode is online.");
    Option optSource = OptionBuilder.create(SOURCE);

    OptionBuilder.withDescription("do random validation of " +
        "data written into ozone, only subset of data is validated.");
    Option optValidateWrite = OptionBuilder.create(VALIDATE_WRITE);

    OptionBuilder.withArgName("value");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("number of threads to be launched " +
        "for the run");
    Option optNumOfThreads = OptionBuilder.create(NUM_OF_THREADS);

    OptionBuilder.withArgName("value");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("specifies number of Volumes to be " +
        "created in offline mode");
    Option optNumOfVolumes = OptionBuilder.create(NUM_OF_VOLUMES);

    OptionBuilder.withArgName("value");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("specifies number of Buckets to be " +
        "created per Volume in offline mode");
    Option optNumOfBuckets = OptionBuilder.create(NUM_OF_BUCKETS);

    OptionBuilder.withArgName("value");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("specifies number of Keys to be " +
        "created per Bucket in offline mode");
    Option optNumOfKeys = OptionBuilder.create(NUM_OF_KEYS);

    OptionBuilder.withArgName("value");
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("specifies the size of Key in bytes to be " +
        "created in offline mode");
    Option optKeySize = OptionBuilder.create(KEY_SIZE);

    OptionBuilder.withArgName(RATIS);
    OptionBuilder.hasArg();
    OptionBuilder.withDescription("Use Ratis as the default replication " +
        "strategy");
    Option optRatis = OptionBuilder.create(RATIS);

    options.addOption(optHelp);
    options.addOption(optMode);
    options.addOption(optSource);
    options.addOption(optValidateWrite);
    options.addOption(optNumOfThreads);
    options.addOption(optNumOfVolumes);
    options.addOption(optNumOfBuckets);
    options.addOption(optNumOfKeys);
    options.addOption(optKeySize);
    options.addOption(optRatis);
    return options;
  }

  private void parseOptions(CommandLine cmdLine) {
    printUsage = cmdLine.hasOption(HELP);

    mode = cmdLine.hasOption(MODE) ?
        cmdLine.getOptionValue(MODE) : MODE_DEFAULT;

    source = cmdLine.hasOption(SOURCE) ?
        cmdLine.getOptionValue(SOURCE) : SOURCE_DEFAULT;

    numOfThreads = cmdLine.hasOption(NUM_OF_THREADS) ?
        cmdLine.getOptionValue(NUM_OF_THREADS) : NUM_OF_THREADS_DEFAULT;

    validateWrites = cmdLine.hasOption(VALIDATE_WRITE);

    numOfVolumes = cmdLine.hasOption(NUM_OF_VOLUMES) ?
        cmdLine.getOptionValue(NUM_OF_VOLUMES) : NUM_OF_VOLUMES_DEFAULT;

    numOfBuckets = cmdLine.hasOption(NUM_OF_BUCKETS) ?
        cmdLine.getOptionValue(NUM_OF_BUCKETS) : NUM_OF_BUCKETS_DEFAULT;

    numOfKeys = cmdLine.hasOption(NUM_OF_KEYS) ?
        cmdLine.getOptionValue(NUM_OF_KEYS) : NUM_OF_KEYS_DEFAULT;

    keySize = cmdLine.hasOption(KEY_SIZE) ?
        Integer.parseInt(cmdLine.getOptionValue(KEY_SIZE)) : KEY_SIZE_DEFAULT;
    useRatis = cmdLine.hasOption(RATIS);

    //To-do if replication factor is not mentioned throw an exception
    replicationFactor = useRatis ?
        Integer.parseInt(cmdLine.getOptionValue(RATIS)) : 0;
  }

  private void usage() {
    System.out.println("Options supported are:");
    System.out.println("-numOfThreads <value>           "
        + "number of threads to be launched for the run.");
    System.out.println("-validateWrites                 "
        + "do random validation of data written into ozone, " +
        "only subset of data is validated.");
    System.out.println("-mode [online | offline]        "
        + "specifies the mode in which Corona should run.");
    System.out.println("-source <url>                   "
        + "specifies the URL of s3 commoncrawl warc file to " +
        "be used when the mode is online.");
    System.out.println("-numOfVolumes <value>           "
        + "specifies number of Volumes to be created in offline mode");
    System.out.println("-numOfBuckets <value>           "
        + "specifies number of Buckets to be created per Volume " +
        "in offline mode");
    System.out.println("-numOfKeys <value>              "
        + "specifies number of Keys to be created per Bucket " +
        "in offline mode");
    System.out.println("-keySize <value>                "
        + "specifies the size of Key in bytes to be created in offline mode");
    System.out.println("-help                           "
        + "prints usage.");
    System.out.println();
  }

  /**
   * Adds ShutdownHook to print statistics.
   */
  private void addShutdownHook() {
    Runtime.getRuntime().addShutdownHook(
        new Thread(() -> printStats(System.out)));
  }

  private Thread getProgressBarThread() {
    Supplier<Long> currentValue;
    long maxValue;

    if (mode.equals("online")) {
      throw new UnsupportedOperationException("Not yet implemented.");
    } else {
      currentValue = () -> numberOfKeysAdded.get();
      maxValue = Long.parseLong(numOfVolumes) *
          Long.parseLong(numOfBuckets) *
          Long.parseLong(numOfKeys);
    }
    Thread progressBarThread = new Thread(
        new ProgressBar(System.out, currentValue, maxValue));
    progressBarThread.setName("ProgressBar");
    return progressBarThread;
  }

  /**
   * Prints stats of {@link Corona} run to the PrintStream.
   *
   * @param out PrintStream
   */
  private void printStats(PrintStream out) {
    int threadCount = Integer.parseInt(numOfThreads);

    long endTime = System.nanoTime() - startTime;
    String execTime = String.format("%02d:%02d:%02d",
        TimeUnit.NANOSECONDS.toHours(endTime),
        TimeUnit.NANOSECONDS.toMinutes(endTime) -
            TimeUnit.HOURS.toMinutes(
                TimeUnit.NANOSECONDS.toHours(endTime)),
        TimeUnit.NANOSECONDS.toSeconds(endTime) -
            TimeUnit.MINUTES.toSeconds(
                TimeUnit.NANOSECONDS.toMinutes(endTime)));

    long volumeTime = volumeCreationTime.longValue();
    String prettyVolumeTime = String.format("%02d:%02d:%02d:%02d",
        TimeUnit.NANOSECONDS.toHours(volumeTime),
        TimeUnit.NANOSECONDS.toMinutes(volumeTime) -
            TimeUnit.HOURS.toMinutes(
                TimeUnit.NANOSECONDS.toHours(volumeTime)),
        TimeUnit.NANOSECONDS.toSeconds(volumeTime) -
            TimeUnit.MINUTES.toSeconds(
                TimeUnit.NANOSECONDS.toMinutes(volumeTime)),
        TimeUnit.NANOSECONDS.toMillis(volumeTime) -
            TimeUnit.SECONDS.toMillis(
                TimeUnit.NANOSECONDS.toSeconds(volumeTime)));

    long bucketTime = bucketCreationTime.longValue() / threadCount;
    String prettyBucketTime = String.format("%02d:%02d:%02d:%02d",
        TimeUnit.NANOSECONDS.toHours(bucketTime),
        TimeUnit.NANOSECONDS.toMinutes(bucketTime) -
            TimeUnit.HOURS.toMinutes(
                TimeUnit.NANOSECONDS.toHours(bucketTime)),
        TimeUnit.NANOSECONDS.toSeconds(bucketTime) -
            TimeUnit.MINUTES.toSeconds(
                TimeUnit.NANOSECONDS.toMinutes(bucketTime)),
        TimeUnit.NANOSECONDS.toMillis(bucketTime) -
            TimeUnit.SECONDS.toMillis(
                TimeUnit.NANOSECONDS.toSeconds(bucketTime)));

    long totalKeyCreationTime = keyCreationTime.longValue() / threadCount;
    String prettyKeyCreationTime = String.format("%02d:%02d:%02d:%02d",
        TimeUnit.NANOSECONDS.toHours(totalKeyCreationTime),
        TimeUnit.NANOSECONDS.toMinutes(totalKeyCreationTime) -
            TimeUnit.HOURS.toMinutes(
                TimeUnit.NANOSECONDS.toHours(totalKeyCreationTime)),
        TimeUnit.NANOSECONDS.toSeconds(totalKeyCreationTime) -
            TimeUnit.MINUTES.toSeconds(
                TimeUnit.NANOSECONDS.toMinutes(totalKeyCreationTime)),
        TimeUnit.NANOSECONDS.toMillis(totalKeyCreationTime) -
            TimeUnit.SECONDS.toMillis(
                TimeUnit.NANOSECONDS.toSeconds(totalKeyCreationTime)));

    long totalKeyWriteTime = keyWriteTime.longValue() / threadCount;
    String prettyKeyWriteTime = String.format("%02d:%02d:%02d:%02d",
        TimeUnit.NANOSECONDS.toHours(totalKeyWriteTime),
        TimeUnit.NANOSECONDS.toMinutes(totalKeyWriteTime) -
            TimeUnit.HOURS.toMinutes(
                TimeUnit.NANOSECONDS.toHours(totalKeyWriteTime)),
        TimeUnit.NANOSECONDS.toSeconds(totalKeyWriteTime) -
            TimeUnit.MINUTES.toSeconds(
                TimeUnit.NANOSECONDS.toMinutes(totalKeyWriteTime)),
        TimeUnit.NANOSECONDS.toMillis(totalKeyWriteTime) -
            TimeUnit.SECONDS.toMillis(
                TimeUnit.NANOSECONDS.toSeconds(totalKeyWriteTime)));

    out.println();
    out.println("***************************************************");
    out.println("Number of Volumes created: " + numberOfVolumesCreated);
    out.println("Number of Buckets created: " + numberOfBucketsCreated);
    out.println("Number of Keys added: " + numberOfKeysAdded);
    out.println("Time spent in volume creation: " + prettyVolumeTime);
    out.println("Time spent in bucket creation: " + prettyBucketTime);
    out.println("Time spent in key creation: " + prettyKeyCreationTime);
    out.println("Time spent in writing keys: " + prettyKeyWriteTime);
    out.println("Total bytes written: " + totalBytesWritten);
    if (validateWrites) {
      out.println("Total number of writes validated: " +
          totalWritesValidated);
      out.println("Writes validated: " +
          (100.0 * totalWritesValidated / numberOfKeysAdded.get())
          + " %");
      out.println("Successful validation: " +
          writeValidationSuccessCount);
      out.println("Unsuccessful validation: " +
          writeValidationFailureCount);
    }
    out.println("Total Execution time: " + execTime);
    out.println("***************************************************");
  }

  /**
   * Returns the number of volumes created.
   * @return volume count.
   */
  @VisibleForTesting
  int getNumberOfVolumesCreated() {
    return numberOfVolumesCreated.get();
  }

  /**
   * Returns the number of buckets created.
   * @return bucket count.
   */
  @VisibleForTesting
  int getNumberOfBucketsCreated() {
    return numberOfBucketsCreated.get();
  }

  /**
   * Returns the number of keys added.
   * @return keys count.
   */
  @VisibleForTesting
  long getNumberOfKeysAdded() {
    return numberOfKeysAdded.get();
  }

  /**
   * Returns true if random validation of write is enabled.
   * @return validateWrites
   */
  @VisibleForTesting
  boolean getValidateWrites() {
    return validateWrites;
  }

  /**
   * Returns the number of keys validated.
   * @return validated key count.
   */
  @VisibleForTesting
  long getTotalKeysValidated() {
    return totalWritesValidated;
  }

  /**
   * Returns the number of successful validation.
   * @return successful validation count.
   */
  @VisibleForTesting
  long getSuccessfulValidationCount() {
    return writeValidationSuccessCount;
  }

  /**
   * Returns the number of unsuccessful validation.
   * @return unsuccessful validation count.
   */
  @VisibleForTesting
  long getUnsuccessfulValidationCount() {
    return writeValidationFailureCount;
  }

  /**
   * Wrapper to hold ozone key-value pair.
   */
  private static class KeyValue {

    /**
     * Bucket name associated with the key-value.
     */
    private OzoneBucket bucket;
    /**
     * Key name associated with the key-value.
     */
    private String key;
    /**
     * Value associated with the key-value.
     */
    private byte[] value;

    /**
     * Constructs a new ozone key-value pair.
     *
     * @param key   key part
     * @param value value part
     */
    KeyValue(OzoneBucket bucket, String key, byte[] value) {
      this.bucket = bucket;
      this.key = key;
      this.value = value;
    }
  }

  private class OfflineProcessor implements Runnable {

    private int totalBuckets;
    private int totalKeys;
    private OzoneVolume volume;

    OfflineProcessor(String volumeName) throws Exception {
      this.totalBuckets = Integer.parseInt(numOfBuckets);
      this.totalKeys = Integer.parseInt(numOfKeys);
      LOG.trace("Creating volume: {}", volumeName);
      long start = System.nanoTime();
      objectStore.createVolume(volumeName);
      volumeCreationTime.getAndAdd(System.nanoTime() - start);
      numberOfVolumesCreated.getAndIncrement();
      volume = objectStore.getVolume(volumeName);
    }

    @Override
    public void run() {
      OzoneProtos.ReplicationType type = OzoneProtos.ReplicationType
          .STAND_ALONE;
      OzoneProtos.ReplicationFactor factor = OzoneProtos.ReplicationFactor.ONE;

      if (useRatis) {
        type = OzoneProtos.ReplicationType.RATIS;
        factor = replicationFactor != 0 ?
            OzoneProtos.ReplicationFactor.valueOf(replicationFactor) :
            OzoneProtos.ReplicationFactor.THREE;

      }
      for (int j = 0; j < totalBuckets; j++) {
        String bucketName = "bucket-" + j + "-" +
            RandomStringUtils.randomNumeric(5);
        try {
          LOG.trace("Creating bucket: {} in volume: {}",
              bucketName, volume.getName());
          long start = System.nanoTime();
          volume.createBucket(bucketName);
          bucketCreationTime.getAndAdd(System.nanoTime() - start);
          numberOfBucketsCreated.getAndIncrement();
          OzoneBucket bucket = volume.getBucket(bucketName);
          for (int k = 0; k < totalKeys; k++) {
            String key = "key-" + k + "-" +
                RandomStringUtils.randomNumeric(5);
            byte[] value = DFSUtil.string2Bytes(
                RandomStringUtils.randomAscii(keySize));
            try {
              LOG.trace("Adding key: {} in bucket: {} of volume: {}",
                  key, bucket, volume);
              long keyCreateStart = System.nanoTime();
              OzoneOutputStream os = bucket.createKey(key, value.length,
                  type, factor);
              keyCreationTime.getAndAdd(System.nanoTime() - keyCreateStart);
              long keyWriteStart = System.nanoTime();
              os.write(value);
              os.close();
              keyWriteTime.getAndAdd(System.nanoTime() - keyWriteStart);
              totalBytesWritten.getAndAdd(value.length);
              numberOfKeysAdded.getAndIncrement();
              if (validateWrites) {
                boolean validate = validationQueue.offer(
                    new KeyValue(bucket, key, value));
                if (validate) {
                  LOG.trace("Key {}, is queued for validation.", key);
                }
              }
            } catch (Exception e) {
              exception = true;
              LOG.error("Exception while adding key: {} in bucket: {}" +
                  " of volume: {}.", key, bucket, volume, e);
            }
          }
        } catch (Exception e) {
          exception = true;
          LOG.error("Exception while creating bucket: {}" +
              " in volume: {}.", bucketName, volume, e);
        }
      }
    }
  }

  private class ProgressBar implements Runnable {

    private static final long REFRESH_INTERVAL = 1000L;

    private PrintStream stream;
    private Supplier<Long> currentValue;
    private long maxValue;

    ProgressBar(PrintStream stream, Supplier<Long> currentValue,
        long maxValue) {
      this.stream = stream;
      this.currentValue = currentValue;
      this.maxValue = maxValue;
    }

    @Override
    public void run() {
      try {
        stream.println();
        long value;
        while ((value = currentValue.get()) < maxValue) {
          print(value);
          if (completed) {
            break;
          }
          Thread.sleep(REFRESH_INTERVAL);
        }
        if (exception) {
          stream.println();
          stream.println("Incomplete termination, " +
              "check log for exception.");
        } else {
          print(maxValue);
        }
        stream.println();
      } catch (InterruptedException e) {
      }
    }

    /**
     * Given current value prints the progress bar.
     *
     * @param value
     */
    private void print(long value) {
      stream.print('\r');
      double percent = 100.0 * value / maxValue;
      StringBuilder sb = new StringBuilder();
      sb.append(" " + String.format("%.2f", percent) + "% |");

      for (int i = 0; i <= percent; i++) {
        sb.append('█');
      }
      for (int j = 0; j < 100 - percent; j++) {
        sb.append(' ');
      }
      sb.append("|  ");
      sb.append(value + "/" + maxValue);
      long timeInSec = TimeUnit.SECONDS.convert(
          System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
      String timeToPrint = String.format("%d:%02d:%02d", timeInSec / 3600,
          (timeInSec % 3600) / 60, timeInSec % 60);
      sb.append(" Time: " + timeToPrint);
      stream.print(sb);
    }
  }

  /**
   * Validates the write done in ozone cluster.
   */
  private class Validator implements Runnable {

    @Override
    public void run() {
      while (!completed) {
        try {
          KeyValue kv = validationQueue.poll(5, TimeUnit.SECONDS);
          if (kv != null) {

            OzoneInputStream is = kv.bucket.readKey(kv.key);
            byte[] value = new byte[kv.value.length];
            int length = is.read(value);
            totalWritesValidated++;
            if (length == kv.value.length && Arrays.equals(value, kv.value)) {
              writeValidationSuccessCount++;
            } else {
              writeValidationFailureCount++;
              LOG.warn("Data validation error for key {}/{}/{}",
                  kv.bucket.getVolumeName(), kv.bucket, kv.key);
              LOG.warn("Expected: {}, Actual: {}",
                  DFSUtil.bytes2String(kv.value),
                  DFSUtil.bytes2String(value));
            }
          }
        } catch (IOException | InterruptedException ex) {
          LOG.error("Exception while validating write: " + ex.getMessage());
        }
      }
    }
  }
}
