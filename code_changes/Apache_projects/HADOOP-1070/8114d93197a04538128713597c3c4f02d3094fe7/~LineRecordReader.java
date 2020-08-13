package org.apache.hadoop.mapred;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

/**
 * Treats keys as offset in file and value as line. 
 * @author sanjaydahiya
 *
 */
public class LineRecordReader implements RecordReader {
  private long start; 
  private long pos;
  private long end;
  private BufferedInputStream in;
  private ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
  /**
   * Provide a bridge to get the bytes from the ByteArrayOutputStream
   * without creating a new byte array.
   */
  private static class TextStuffer extends OutputStream {
    public Text target;
    public void write(int b) {
      throw new UnsupportedOperationException("write(byte) not supported");
    }
    public void write(byte[] data, int offset, int len) throws IOException {
      target.set(data, offset, len);
    }      
  }
  private TextStuffer bridge = new TextStuffer();

  public LineRecordReader(InputStream in, long offset, long endOffset) 
    throws IOException{
    this.in = new BufferedInputStream(in);
    this.start = offset;
    this.pos = offset;
    this.end = endOffset;    
//    readLine(in, null); 
  }
  
  public WritableComparable createKey() {
    return new LongWritable();
  }
  
  public Writable createValue() {
    return new Text();
  }
  
  /** Read a line. */
  public synchronized boolean next(Writable key, Writable value)
    throws IOException {
    if (pos >= end)
      return false;

    ((LongWritable)key).set(pos);           // key is position
    buffer.reset();
    long bytesRead = readLine(in, buffer);
    if (bytesRead == 0) {
      return false;
    }
    pos += bytesRead;
    bridge.target = (Text) value;
    buffer.writeTo(bridge);
    return true;
  }

  public static long readLine(InputStream in, 
      OutputStream out) throws IOException {
    long bytes = 0;
    while (true) {
      
      int b = in.read();
      if (b == -1) {
        break;
      }
      bytes += 1;
      
      byte c = (byte)b;
      if (c == '\n') {
        break;
      }
      
      if (c == '\r') {
        in.mark(1);
        byte nextC = (byte)in.read();
        if (nextC != '\n') {
          in.reset();
        } else {
          bytes += 1;
        }
        break;
      }
      
      if (out != null) {
        out.write(c);
      }
    }
    return bytes;
  }
  
  /**
   * Get the progress within the split
   */
  public float getProgress() {
    if (start == end) {
      return 0.0f;
    } else {
      return (pos - start) / (end - start);
    }
  }
  
  public  synchronized long getPos() throws IOException {
    return pos;
  }

  public synchronized void close() throws IOException { 
    in.close(); 
  }
}
