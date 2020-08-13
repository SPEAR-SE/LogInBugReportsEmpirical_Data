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
package org.apache.hadoop.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface Decryptor {
  
  /**
   * Initialize the decryptor, the internal decryption context will be 
   * reset.
   * @param key decryption key.
   * @param iv decryption initialization vector
   * @throws IOException if initialization fails
   */
  public void init(byte[] key, byte[] iv) throws IOException;
  
  /**
   * Indicate whether decryption context is reset.
   * <p/>
   * It's useful for some mode like CTR which requires different IV for 
   * different parts of data. Usually decryptor can maintain the context 
   * internally such as calculating IV/counter, then continue a multiple-part 
   * decryption operation without reinit the decryptor using key and the new 
   * IV. For mode like CTR, if context is reset after each decryption, the 
   * decryptor should be reinit before each operation, that's not efficient. 
   * @return boolean whether context is reset.
   */
  public boolean isContextReset();
  
  /**
   * This exposes a direct interface for record decryption with direct byte
   * buffers.
   * <p/>
   * The decrypt() function need not always consume the buffers provided,
   * it will need to be called multiple times to decrypt an entire buffer 
   * and the object will hold the decryption context internally.
   * <p/>
   * Some implementation may need enough space in the destination buffer to 
   * decrypt an entire input.
   * <p/>
   * The end result will move inBuffer.position() by the bytes-read and
   * outBuffer.position() by the bytes-written. It should not modify the 
   * inBuffer.limit() or outBuffer.limit() to maintain consistency of operation.
   * <p/>
   * @param inBuffer in direct {@link ByteBuffer} for reading from. Requires 
   * inBuffer != null and inBuffer.remaining() > 0
   * @param outBuffer out direct {@link ByteBuffer} for storing the results
   * into. Requires outBuffer != null and outBuffer.remaining() > 0
   * @throws IOException if decryption fails
   */
  public void decrypt(ByteBuffer inBuffer, ByteBuffer outBuffer) 
      throws IOException;
}
