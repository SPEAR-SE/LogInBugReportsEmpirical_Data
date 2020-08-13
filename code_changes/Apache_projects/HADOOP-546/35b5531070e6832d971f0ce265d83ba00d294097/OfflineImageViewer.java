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
package org.apache.hadoop.hdfs.tools.offlineImageViewer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;

/**
 * OfflineImageViewer to dump the contents of an Hadoop image file to XML
 * or the console.  Main entry point into utility, either via the
 * command line or programatically.
 */
public class OfflineImageViewer {
  private final static String usage = 
    "Usage: bin/hdfs oiv [OPTIONS] -i INPUTFILE -o OUTPUTFILE\n" +
    "Offline Image Viewer\n" + 
    "View a Hadoop fsimage INPUTFILE using the specified PROCESSOR,\n" +
    "saving the results in OUTPUTFILE.\n" +
    "\n" +
    "The oiv utility will attempt to parse correctly formed image files\n" +
    "and will abort fail with mal-formed image files. Currently the\n" +
    "supports FSImage layout versions -16 through -19.\n" +
    "\n" +
    "The tool works offline and does not require a running cluster in\n" +
    "order to process an image file.\n" +
    "\n" +
    "The following image processors are available:\n" +
    "  * Ls: The default image processor generates an lsr-style listing\n" +
    "    of the files in the namespace, with the same fields in the same\n" +
    "    order.  Note that in order to correctly determine file sizes,\n" +
    "    this formatter cannot skip blocks and will override the\n" +
    "    -skipBlocks option.\n" +
    "  * Indented: This processor enumerates over all of the elements in\n" +
    "    the fsimage file, using levels of indentation to delineate\n" +
    "    sections within the file.\n" +
    "  * XML: This processor creates an XML document with all elements of\n" +
    "    the fsimage enumerated, suitable for further analysis by XML\n" +
    "    tools.\n" +
    "\n" + 
    "Required command line arguments:\n" +
    "-i,--inputFile <arg>   FSImage file to process.\n" +
    "-o,--outputFile <arg>  Name of output file. If the specified\n" +
    "                       file exists, it will be overwritten.\n" +
    "\n" + 
    "Optional command line arguments:\n" +
    "-p,--processor <arg>   Select which type of processor to apply\n" +
    "                       against image file. (Ls|XML|Indented).\n" +
    "-h,--help              Display usage information and exit\n" +
    "-printToScreen         For processors that write to a file, also\n" +
    "                       output to screen. On large image files this\n" +
    "                       will dramatically increase processing time.\n" +
    "-skipBlocks            Skip inodes' blocks information. May\n" +
    "                       significantly decrease output.\n" +
    "                       (default = false).\n";

  private final boolean skipBlocks;
  private final String inputFile;
  private final ImageVisitor processor;
  
  public OfflineImageViewer(String inputFile, ImageVisitor processor, 
             boolean skipBlocks) {
    this.inputFile = inputFile;
    this.processor = processor;
    this.skipBlocks = skipBlocks;
  }

  /**
   * Process image file.
   */
  public void go() throws IOException  {
    DataInputStream in = null;

    try {
      in = new DataInputStream(new BufferedInputStream(
               new FileInputStream(new File(inputFile))));

      int imageVersionFile = findImageVersion(in);

      ImageLoader fsip =
             ImageLoader.LoaderFactory.getLoader(imageVersionFile);

      if(fsip == null) 
        throw new IOException("No image processor to read version " +
            imageVersionFile + " is available.");

      fsip.loadImage(in, processor, skipBlocks);

    } finally {
      if(in != null) in.close();
    }
  }

  /**
   * Check an fsimage datainputstream's version number.
   *
   * The datainput stream is returned at the same point as it was passed in;
   * this method has no effect on the datainputstream's read pointer.
   *
   * @param in Datainputstream of fsimage
   * @return Filesystem layout version of fsimage represented by stream
   * @throws IOException If problem reading from in
   */
  private int findImageVersion(DataInputStream in) throws IOException {
    in.mark(42); // arbitrary amount, resetting immediately

    int version = in.readInt();
    in.reset();

    return version;
  }
  
  /**
   * Build command-line options and descriptions
   */
  public static Options buildOptions() {
    Options options = new Options();

    // Build in/output file arguments, which are required, but there is no 
    // addOption method that can specify this
    OptionBuilder.isRequired();
    OptionBuilder.hasArgs();
    OptionBuilder.withLongOpt("outputFile");
    options.addOption(OptionBuilder.create("o"));
    
    OptionBuilder.isRequired();
    OptionBuilder.hasArgs();
    OptionBuilder.withLongOpt("inputFile");
    options.addOption(OptionBuilder.create("i"));
    
    options.addOption("p", "processor", true, "");
    options.addOption("h", "help", false, "");
    options.addOption("skipBlocks", false, "");
    options.addOption("printToScreen", false, "");

    return options;
  }
  
  /**
   * Entry point to command-line-driven operation.  User may specify
   * options and start fsimage viewer from the command line.  Program
   * will process image file and exit cleanly or, if an error is
   * encountered, inform user and exit.
   *
   * @param args Command line options
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    Options options = buildOptions();
    if(args.length == 0) {
      printUsage();
      return;
    }
    
    CommandLineParser parser = new PosixParser();
    CommandLine cmd;

    try {
      cmd = parser.parse(options, args);
    } catch (ParseException e) {
      System.out.println("Error parsing command-line options: ");
      printUsage();
      return;
    }

    if(cmd.hasOption("h")) { // print help and exit
      printUsage();
      return;
    }

    boolean skipBlocks = cmd.hasOption("skipBlocks");
    boolean printToScreen = cmd.hasOption("printToScreen");
    String inputFile = cmd.getOptionValue("i");
    String processor = cmd.getOptionValue("p", "Ls");
    String outputFile;
    
    ImageVisitor v;
    if(processor.equals("Indented")) {
      outputFile = cmd.getOptionValue("o");
      v = new IndentedImageVisitor(outputFile, printToScreen);
    } else if (processor.equals("XML")) {
      outputFile = cmd.getOptionValue("o");
      v = new XmlImageVisitor(outputFile, printToScreen);
    } else {
      outputFile = cmd.getOptionValue("o");
      v = new LsImageVisitor(outputFile, printToScreen);
      skipBlocks = false;
    }
    
    try {
      OfflineImageViewer d = new OfflineImageViewer(inputFile, v, skipBlocks);
      d.go();
    } catch (EOFException e) {
      System.err.println("Input file ended unexpectedly.  Exiting");
    } catch(IOException e) {
      System.err.println("Encountered exception.  Exiting: " + e.getMessage());
    }
  }

  /**
   * Print application usage instructions.
   */
  private static void printUsage() {
    System.out.println(usage);
  }
}
