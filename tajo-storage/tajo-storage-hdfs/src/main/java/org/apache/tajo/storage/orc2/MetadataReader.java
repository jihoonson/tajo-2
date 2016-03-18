/*
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

package org.apache.tajo.storage.orc2;

import com.google.common.collect.Lists;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.io.DiskRange;
import org.apache.orc.CompressionCodec;
import org.apache.orc.OrcProto;
import org.apache.orc.StripeInformation;
import org.apache.orc.impl.BufferChunk;
import org.apache.orc.impl.InStream;
import org.apache.orc.impl.OrcIndex;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public class MetadataReader implements Closeable {

  private final FSDataInputStream file;
  private final CompressionCodec codec;
  private final int bufferSize;
  private final int typeCount;

  public MetadataReader(FileSystem fileSystem, Path path,
                        CompressionCodec codec, int bufferSize, int typeCount) throws IOException {
    this(fileSystem.open(path), codec, bufferSize, typeCount);
  }

  public MetadataReader(FSDataInputStream file,
                        CompressionCodec codec, int bufferSize, int typeCount) {
    this.file = file;
    this.codec = codec;
    this.bufferSize = bufferSize;
    this.typeCount = typeCount;
  }

  public OrcIndex readRowIndex(OrcProto.StripeInformation stripe,
                               OrcProto.StripeFooter footer, boolean[] included, OrcProto.RowIndex[] indexes,
                               boolean[] sargColumns, OrcProto.BloomFilterIndex[] bloomFilterIndices) throws IOException {
    if (footer == null) {
      footer = readStripeFooter(stripe);
    }
    if (indexes == null) {
      indexes = new OrcProto.RowIndex[typeCount];
    }
    if (bloomFilterIndices == null) {
      bloomFilterIndices = new OrcProto.BloomFilterIndex[typeCount];
    }
    long offset = stripe.getOffset();
    List<OrcProto.Stream> streams = footer.getStreamsList();
    for (int i = 0; i < streams.size(); i++) {
      OrcProto.Stream stream = streams.get(i);
      OrcProto.Stream nextStream = null;
      if (i < streams.size() - 1) {
        nextStream = streams.get(i+1);
      }
      int col = stream.getColumn();
      int len = (int) stream.getLength();
      // row index stream and bloom filter are interlaced, check if the sarg column contains bloom
      // filter and combine the io to read row index and bloom filters for that column together
      if (stream.hasKind() && (stream.getKind() == OrcProto.Stream.Kind.ROW_INDEX)) {
        boolean readBloomFilter = false;
        if (sargColumns != null && sargColumns[col] &&
            nextStream.getKind() == OrcProto.Stream.Kind.BLOOM_FILTER) {
          len += nextStream.getLength();
          i += 1;
          readBloomFilter = true;
        }
        if ((included == null || included[col]) && indexes[col] == null) {
          byte[] buffer = new byte[len];
          file.readFully(offset, buffer, 0, buffer.length);
          ByteBuffer bb = ByteBuffer.wrap(buffer);
          indexes[col] = OrcProto.RowIndex.parseFrom(InStream.create("index",
              Lists.<DiskRange>newArrayList(new BufferChunk(bb, 0)), stream.getLength(),
              codec, bufferSize));
          if (readBloomFilter) {
            bb.position((int) stream.getLength());
            bloomFilterIndices[col] = OrcProto.BloomFilterIndex.parseFrom(InStream.create(
                "bloom_filter", Lists.<DiskRange>newArrayList(new BufferChunk(bb, 0)),
                nextStream.getLength(), codec, bufferSize));
          }
        }
      }
      offset += len;
    }

    OrcIndex index = new OrcIndex(indexes, bloomFilterIndices);
    return index;
  }

  public OrcProto.StripeFooter readStripeFooter(OrcProto.StripeInformation stripe) throws IOException {
    long offset = stripe.getOffset() + stripe.getIndexLength() + stripe.getDataLength();
    int tailLength = (int) stripe.getFooterLength();

    // read the footer
    ByteBuffer tailBuf = ByteBuffer.allocate(tailLength);
    file.readFully(offset, tailBuf.array(), tailBuf.arrayOffset(), tailLength);
    return OrcProto.StripeFooter.parseFrom(InStream.createCodedInputStream("footer",
        Lists.<DiskRange>newArrayList(new BufferChunk(tailBuf, 0)),
        tailLength, codec, bufferSize));
  }

  @Override
  public void close() throws IOException {
    file.close();
  }
}