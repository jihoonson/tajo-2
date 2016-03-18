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

package org.apache.tajo.storage.orc2;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.io.Text;
import org.apache.orc.OrcProto;
import org.apache.orc.impl.*;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.catalog.TypeDesc;
import org.apache.tajo.datum.Datum;
import org.apache.tajo.datum.DatumFactory;
import org.apache.tajo.datum.NullDatum;
import org.apache.tajo.datum.TextDatum;
import org.apache.tajo.exception.TajoRuntimeException;
import org.apache.tajo.exception.UnsupportedException;
import org.apache.tajo.util.datetime.DateTimeUtil;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class TreeReaderFactory {

  private final static Log LOG = LogFactory.getLog(TreeReaderFactory.class);

  public static class TreeReaderSchema {

    /**
     * The types in the ORC file.
     */
    List<OrcProto.Type> fileTypes;

    /**
     * The treeReaderSchema that the reader should read as.
     */
    List<OrcProto.Type> schemaTypes;

    /**
     * The subtype of the row STRUCT.  Different than 0 for ACID.
     */
    int innerStructSubtype;

    public TreeReaderSchema() {
      fileTypes = null;
      schemaTypes = null;
      innerStructSubtype = -1;
    }

    public TreeReaderSchema fileTypes(List<OrcProto.Type> fileTypes) {
      this.fileTypes = fileTypes;
      return this;
    }

    public TreeReaderSchema schemaTypes(List<OrcProto.Type> schemaTypes) {
      this.schemaTypes = schemaTypes;
      return this;
    }

    public TreeReaderSchema innerStructSubtype(int innerStructSubtype) {
      this.innerStructSubtype = innerStructSubtype;
      return this;
    }

    public List<OrcProto.Type> getFileTypes() {
      return fileTypes;
    }

    public List<OrcProto.Type> getSchemaTypes() {
      return schemaTypes;
    }

    public int getInnerStructSubtype() {
      return innerStructSubtype;
    }
  }

  public abstract static class TreeReader {
    protected final int columnId;
    protected BitFieldReader present = null;
    protected boolean valuePresent = false;

    TreeReader(int columnId) throws IOException {
      this(columnId, null);
    }

    protected TreeReader(int columnId, InStream in) throws IOException {
      this.columnId = columnId;
      if (in == null) {
        present = null;
        valuePresent = true;
      } else {
        present = new BitFieldReader(in, 1);
      }
    }

    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    static IntegerReader createIntegerReader(OrcProto.ColumnEncoding.Kind kind,
                                             InStream in,
                                             boolean signed, boolean skipCorrupt) throws IOException {
      switch (kind) {
        case DIRECT_V2:
        case DICTIONARY_V2:
          return new RunLengthIntegerReaderV2(in, signed, skipCorrupt);
        case DIRECT:
        case DICTIONARY:
          return new RunLengthIntegerReader(in, signed);
        default:
          throw new IllegalArgumentException("Unknown encoding " + kind);
      }
    }

    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      checkEncoding(stripeFooter.getColumnsList().get(columnId));
      InStream in = streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.PRESENT));
      if (in == null) {
        present = null;
        valuePresent = true;
      } else {
        present = new BitFieldReader(in, 1);
      }
    }

    /**
     * Seek to the given position.
     *
     * @param index the indexes loaded from the file
     * @throws IOException
     */
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    public void seek(PositionProvider index) throws IOException {
      if (present != null) {
        present.seek(index);
      }
    }

    protected long countNonNulls(long rows) throws IOException {
      if (present != null) {
        long result = 0;
        for (long c = 0; c < rows; ++c) {
          if (present.next() == 1) {
            result += 1;
          }
        }
        return result;
      } else {
        return rows;
      }
    }

    abstract void skipRows(long rows) throws IOException;

    Datum next() throws IOException {
      if (present != null) {
        valuePresent = present.next() == 1;
      }
      return NullDatum.get();
    }

    public BitFieldReader getPresent() {
      return present;
    }
  }

  public static class BooleanTreeReader extends TreeReader {
    protected BitFieldReader reader = null;

    BooleanTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected BooleanTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present);
      if (data != null) {
        reader = new BitFieldReader(data, 1);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      reader = new BitFieldReader(streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)), 1);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createBool(reader.next() == 1) : NullDatum.get();
    }
  }

  public static class ByteTreeReader extends TreeReader {
    protected RunLengthByteReader reader = null;

    ByteTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected ByteTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present);
      this.reader = new RunLengthByteReader(data);
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      reader = new RunLengthByteReader(streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)));
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createBit(reader.next()) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class ShortTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    ShortTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null);
    }

    protected ShortTreeReader(int columnId, InStream present, InStream data,
                              OrcProto.ColumnEncoding encoding)
        throws IOException {
      super(columnId, present);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, false);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(name), true, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createInt2((short) reader.next()) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class InetTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    InetTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null);
    }

    protected InetTreeReader(int columnId, InStream present, InStream data,
                             OrcProto.ColumnEncoding encoding)
        throws IOException {
      super(columnId, present);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, false);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(name), true, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createInet4((int) reader.next()) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class IntTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    IntTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null);
    }

    protected IntTreeReader(int columnId, InStream present, InStream data,
                            OrcProto.ColumnEncoding encoding)
        throws IOException {
      super(columnId, present);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, false);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(name), true, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createInt4((int) reader.next()) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class LongTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    LongTreeReader(int columnId, boolean skipCorrupt) throws IOException {
      this(columnId, null, null, null, skipCorrupt);
    }

    protected LongTreeReader(int columnId, InStream present, InStream data,
                             OrcProto.ColumnEncoding encoding,
                             boolean skipCorrupt)
        throws IOException {
      super(columnId, present);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        this.reader = createIntegerReader(encoding.getKind(), data, true, skipCorrupt);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(name), true, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createInt8(reader.next()) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  public static class FloatTreeReader extends TreeReader {
    protected InStream stream;
    private final SerializationUtils utils;

    FloatTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected FloatTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present);
      this.utils = new SerializationUtils();
      this.stream = data;
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createFloat4(utils.readFloat(stream)) : NullDatum.get();
    }

    @Override
    protected void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      for (int i = 0; i < items; ++i) {
        utils.readFloat(stream);
      }
    }
  }

  public static class DoubleTreeReader extends TreeReader {
    protected InStream stream;
    private final SerializationUtils utils;

    DoubleTreeReader(int columnId) throws IOException {
      this(columnId, null, null);
    }

    protected DoubleTreeReader(int columnId, InStream present, InStream data) throws IOException {
      super(columnId, present);
      this.utils = new SerializationUtils();
      this.stream = data;
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name =
          new StreamName(columnId,
              OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ? DatumFactory.createFloat8(utils.readDouble(stream)) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long len = items * 8;
      while (len > 0) {
        len -= stream.skip(len);
      }
    }
  }

  public static class BinaryTreeReader extends TreeReader {
    protected InStream stream;
    protected IntegerReader lengths = null;
    protected final LongColumnVector scratchlcv;

    BinaryTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null, null);
    }

    protected BinaryTreeReader(int columnId, InStream present, InStream data, InStream length,
                               OrcProto.ColumnEncoding encoding) throws IOException {
      super(columnId, present);
      scratchlcv = new LongColumnVector();
      this.stream = data;
      if (length != null && encoding != null) {
        checkEncoding(encoding);
        this.lengths = createIntegerReader(encoding.getKind(), length, false, false);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
      lengths = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(new StreamName(columnId, OrcProto.Stream.Kind.LENGTH)), false, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
      lengths.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();

      if (valuePresent) {
        int len = (int) lengths.next();
        byte[] buf = new byte[len];
        int offset = 0;
        while (len > 0) {
          int written = stream.read(buf, offset, len);
          if (written < 0) {
            throw new EOFException("Can't finish byte read from " + stream);
          }
          len -= written;
          offset += written;
        }
        return DatumFactory.createBlob(buf);
      } else {
        return NullDatum.get();
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long lengthToSkip = 0;
      for (int i = 0; i < items; ++i) {
        lengthToSkip += lengths.next();
      }
      while (lengthToSkip > 0) {
        lengthToSkip -= stream.skip(lengthToSkip);
      }
    }
  }

  public static class TimestampTreeReader extends TreeReader {
    protected IntegerReader data = null;
    protected IntegerReader nanos = null;
    private final boolean skipCorrupt;
    private Map<String, Long> baseTimestampMap;
    private long base_timestamp;
    private final TimeZone readerTimeZone;
    private TimeZone writerTimeZone;
    private boolean hasSameTZRules;

    TimestampTreeReader(int columnId, boolean skipCorrupt) throws IOException {
      this(columnId, null, null, null, null, skipCorrupt);
    }

    protected TimestampTreeReader(int columnId, InStream presentStream, InStream dataStream,
                                  InStream nanosStream, OrcProto.ColumnEncoding encoding, boolean skipCorrupt)
        throws IOException {
      super(columnId, presentStream);
      this.skipCorrupt = skipCorrupt;
      this.baseTimestampMap = new HashMap<>();
      this.readerTimeZone = TimeZone.getDefault();
      this.writerTimeZone = readerTimeZone;
      this.hasSameTZRules = writerTimeZone.hasSameRules(readerTimeZone);
      this.base_timestamp = getBaseTimestamp(readerTimeZone.getID());
      if (encoding != null) {
        checkEncoding(encoding);

        if (dataStream != null) {
          this.data = createIntegerReader(encoding.getKind(), dataStream, true, skipCorrupt);
        }

        if (nanosStream != null) {
          this.nanos = createIntegerReader(encoding.getKind(), nanosStream, false, skipCorrupt);
        }
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      data = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(new StreamName(columnId,
              OrcProto.Stream.Kind.DATA)), true, skipCorrupt);
      nanos = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(new StreamName(columnId,
              OrcProto.Stream.Kind.SECONDARY)), false, skipCorrupt);
      base_timestamp = getBaseTimestamp(stripeFooter.getWriterTimezone());
    }

    private long getBaseTimestamp(String timeZoneId) throws IOException {
      // to make sure new readers read old files in the same way
      if (timeZoneId == null || timeZoneId.isEmpty()) {
        timeZoneId = readerTimeZone.getID();
      }

      if (!baseTimestampMap.containsKey(timeZoneId)) {
        writerTimeZone = TimeZone.getTimeZone(timeZoneId);
        hasSameTZRules = writerTimeZone.hasSameRules(readerTimeZone);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(writerTimeZone);
        try {
          long epoch =
              sdf.parse(WriterImpl.BASE_TIMESTAMP_STRING).getTime() / WriterImpl.MILLIS_PER_SECOND;
          baseTimestampMap.put(timeZoneId, epoch);
          return epoch;
        } catch (ParseException e) {
          throw new IOException("Unable to create base timestamp", e);
        } finally {
          sdf.setTimeZone(readerTimeZone);
        }
      }

      return baseTimestampMap.get(timeZoneId);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      data.seek(index);
      nanos.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();

      if (valuePresent) {
        long millis = (data.next() + base_timestamp) * WriterImpl.MILLIS_PER_SECOND;
        int newNanos = parseNanos(nanos.next());
        // fix the rounding when we divided by 1000.
        if (millis >= 0) {
          millis += newNanos / 1000000;
        } else {
          millis -= newNanos / 1000000;
        }
        long offset = 0;
        // If reader and writer time zones have different rules, adjust the timezone difference
        // between reader and writer taking day light savings into account.
        if (!hasSameTZRules) {
          offset = writerTimeZone.getOffset(millis) - readerTimeZone.getOffset(millis);
        }
        long adjustedMillis = millis + offset;
//        Timestamp ts = new Timestamp(adjustedMillis);

        // Sometimes the reader timezone might have changed after adding the adjustedMillis.
        // To account for that change, check for any difference in reader timezone after
        // adding adjustedMillis. If so use the new offset (offset at adjustedMillis point of time).
        if (!hasSameTZRules &&
            (readerTimeZone.getOffset(millis) != readerTimeZone.getOffset(adjustedMillis))) {
          long newOffset =
              writerTimeZone.getOffset(millis) - readerTimeZone.getOffset(adjustedMillis);
          adjustedMillis = millis + newOffset;
//          ts.setTime(adjustedMillis);
        }
//        ts.setNanos(newNanos);
        // TODO: check the below is valid
        return DatumFactory.createTimestamp(DateTimeUtil.javaTimeToJulianTime(adjustedMillis));
      } else {
        return NullDatum.get();
      }
    }

    private static int parseNanos(long serialized) {
      int zeros = 7 & (int) serialized;
      int result = (int) (serialized >>> 3);
      if (zeros != 0) {
        for (int i = 0; i <= zeros; ++i) {
          result *= 10;
        }
      }
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      data.skip(items);
      nanos.skip(items);
    }
  }

  public static class DateTreeReader extends TreeReader {
    protected IntegerReader reader = null;

    DateTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null);
    }

    protected DateTreeReader(int columnId, InStream present, InStream data,
                             OrcProto.ColumnEncoding encoding) throws IOException {
      super(columnId, present);
      if (data != null && encoding != null) {
        checkEncoding(encoding);
        reader = createIntegerReader(encoding.getKind(), data, true, false);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(name), true, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      return valuePresent ?
          DatumFactory.createDate((int) reader.next() + DateTimeUtil.DAYS_FROM_JULIAN_TO_EPOCH) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  /**
   * A tree reader that will read string columns. At the start of the
   * stripe, it creates an internal reader based on whether a direct or
   * dictionary encoding was used.
   */
  public static class StringTreeReader extends TreeReader {
    protected TreeReader reader;

    StringTreeReader(int columnId) throws IOException {
      super(columnId);
    }

    protected StringTreeReader(int columnId, InStream present, InStream data, InStream length,
                               InStream dictionary, OrcProto.ColumnEncoding encoding) throws IOException {
      super(columnId, present);
      if (encoding != null) {
        switch (encoding.getKind()) {
          case DIRECT:
          case DIRECT_V2:
            reader = new StringDirectTreeReader(columnId, present, data, length,
                encoding.getKind());
            break;
          case DICTIONARY:
          case DICTIONARY_V2:
            reader = new StringDictionaryTreeReader(columnId, present, data, length, dictionary,
                encoding);
            break;
          default:
            throw new IllegalArgumentException("Unsupported encoding " +
                encoding.getKind());
        }
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      reader.checkEncoding(encoding);
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      // For each stripe, checks the encoding and initializes the appropriate
      // reader
      switch (stripeFooter.getColumnsList().get(columnId).getKind()) {
        case DIRECT:
        case DIRECT_V2:
          reader = new StringDirectTreeReader(columnId);
          break;
        case DICTIONARY:
        case DICTIONARY_V2:
          reader = new StringDictionaryTreeReader(columnId);
          break;
        default:
          throw new IllegalArgumentException("Unsupported encoding " +
              stripeFooter.getColumnsList().get(columnId).getKind());
      }
      reader.startStripe(streams, stripeFooter);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      reader.seek(index);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      return reader.next();
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skipRows(items);
    }
  }

  private final static class BasicTextReaderShim {
    private final InputStream in;

    public BasicTextReaderShim(InputStream in) {
      this.in = in;
    }

    public byte[] read(int len) throws IOException {
      int offset = 0;
      byte[] bytes = new byte[len];
      while (len > 0) {
        int written = in.read(bytes, offset, len);
        if (written < 0) {
          throw new EOFException("Can't finish read from " + in + " read "
              + (offset) + " bytes out of " + bytes.length);
        }
        len -= written;
        offset += written;
      }
      return bytes;
    }
  }

  /**
   * A reader for string columns that are direct encoded in the current
   * stripe.
   */
  public static class StringDirectTreeReader extends TreeReader {
    protected InStream stream;
    protected BasicTextReaderShim data;
    protected IntegerReader lengths;
    private final LongColumnVector scratchlcv;

    StringDirectTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null, null);
    }

    protected StringDirectTreeReader(int columnId, InStream present, InStream data,
                                     InStream length, OrcProto.ColumnEncoding.Kind encoding) throws IOException {
      super(columnId, present);
      this.scratchlcv = new LongColumnVector();
      this.stream = data;
      if (length != null && encoding != null) {
        this.lengths = createIntegerReader(encoding, length, false, false);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT &&
          encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
      data = new BasicTextReaderShim(stream);

      lengths = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(new StreamName(columnId, OrcProto.Stream.Kind.LENGTH)),
          false, false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      stream.seek(index);
      // don't seek data stream
      lengths.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      int len = (int) lengths.next();
      return valuePresent ? DatumFactory.createText(data.read(len)) : NullDatum.get();
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long lengthToSkip = 0;
      for (int i = 0; i < items; ++i) {
        lengthToSkip += lengths.next();
      }

      while (lengthToSkip > 0) {
        lengthToSkip -= stream.skip(lengthToSkip);
      }
    }

    public IntegerReader getLengths() {
      return lengths;
    }

    public InStream getStream() {
      return stream;
    }
  }

  /**
   * A reader for string columns that are dictionary encoded in the current
   * stripe.
   */
  public static class StringDictionaryTreeReader extends TreeReader {
    private DynamicByteArray dictionaryBuffer;
    private int[] dictionaryOffsets;
    protected IntegerReader reader;

    private byte[] dictionaryBufferInBytesCache = null;
    private final LongColumnVector scratchlcv;
    private final Text result = new Text();

    StringDictionaryTreeReader(int columnId) throws IOException {
      this(columnId, null, null, null, null, null);
    }

    protected StringDictionaryTreeReader(int columnId, InStream present, InStream data,
                                         InStream length, InStream dictionary, OrcProto.ColumnEncoding encoding)
        throws IOException {
      super(columnId, present);
      scratchlcv = new LongColumnVector();
      if (data != null && encoding != null) {
        this.reader = createIntegerReader(encoding.getKind(), data, false, false);
      }

      if (dictionary != null && encoding != null) {
        readDictionaryStream(dictionary);
      }

      if (length != null && encoding != null) {
        readDictionaryLengthStream(length, encoding);
      }
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DICTIONARY &&
          encoding.getKind() != OrcProto.ColumnEncoding.Kind.DICTIONARY_V2) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      super.startStripe(streams, stripeFooter);

      // read the dictionary blob
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DICTIONARY_DATA);
      InStream in = streams.get(name);
      readDictionaryStream(in);

      // read the lengths
      name = new StreamName(columnId, OrcProto.Stream.Kind.LENGTH);
      in = streams.get(name);
      readDictionaryLengthStream(in, stripeFooter.getColumnsList().get(columnId));

      // set up the row reader
      name = new StreamName(columnId, OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(stripeFooter.getColumnsList().get(columnId).getKind(),
          streams.get(name), false, false);
    }

    private void readDictionaryLengthStream(InStream in, OrcProto.ColumnEncoding encoding)
        throws IOException {
      int dictionarySize = encoding.getDictionarySize();
      if (in != null) { // Guard against empty LENGTH stream.
        IntegerReader lenReader = createIntegerReader(encoding.getKind(), in, false, false);
        int offset = 0;
        if (dictionaryOffsets == null ||
            dictionaryOffsets.length < dictionarySize + 1) {
          dictionaryOffsets = new int[dictionarySize + 1];
        }
        for (int i = 0; i < dictionarySize; ++i) {
          dictionaryOffsets[i] = offset;
          offset += (int) lenReader.next();
        }
        dictionaryOffsets[dictionarySize] = offset;
        in.close();
      }

    }

    private void readDictionaryStream(InStream in) throws IOException {
      if (in != null) { // Guard against empty dictionary stream.
        if (in.available() > 0) {
          dictionaryBuffer = new DynamicByteArray(64, in.available());
          dictionaryBuffer.readAll(in);
          // Since its start of strip invalidate the cache.
          dictionaryBufferInBytesCache = null;
        }
        in.close();
      } else {
        dictionaryBuffer = null;
      }
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      seek(index[columnId]);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      super.seek(index);
      reader.seek(index);
    }

    @Override
    Datum next() throws IOException {
      super.next();
      if (valuePresent) {
        int entry = (int) reader.next();
        int offset = dictionaryOffsets[entry];
        int length = getDictionaryEntryLength(entry, offset);
        // If the column is just empty strings, the size will be zero,
        // so the buffer will be null, in that case just return result
        // as it will default to empty
        if (dictionaryBuffer != null) {
          dictionaryBuffer.setText(result, offset, length);
        } else {
          result.clear();
        }
        return DatumFactory.createText(result.getBytes());
      } else {
        return NullDatum.get();
      }
    }

    int getDictionaryEntryLength(int entry, int offset) {
      final int length;
      // if it isn't the last entry, subtract the offsets otherwise use
      // the buffer length.
      if (entry < dictionaryOffsets.length - 1) {
        length = dictionaryOffsets[entry + 1] - offset;
      } else {
        length = dictionaryBuffer.size() - offset;
      }
      return length;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }

    public IntegerReader getReader() {
      return reader;
    }
  }

  public static class CharTreeReader extends StringTreeReader {
    int maxLength;

    CharTreeReader(int columnId, int maxLength) throws IOException {
      this(columnId, maxLength, null, null, null, null, null);
    }

    protected CharTreeReader(int columnId, int maxLength, InStream present, InStream data,
                             InStream length, InStream dictionary, OrcProto.ColumnEncoding encoding) throws IOException {
      super(columnId, present, data, length, dictionary, encoding);
      this.maxLength = maxLength;
    }

    @Override
    Datum next() throws IOException {
      // Use the string reader implementation to populate the internal Text value
      Datum textVal = super.next();
      if (textVal == null) {
        return NullDatum.get();
      }
      // result should now hold the value that was read in.
      // enforce char length
      // TODO: improve this
      if (textVal.isNotNull()) {
        TextDatum datum = (TextDatum) textVal;
        return DatumFactory.createChar(datum.asTextBytes());
      } else {
        return NullDatum.get();
      }
    }
  }

//  protected static class StructTreeReader extends TreeReader {
//    private final int fileColumnCount;
//    private final int resultColumnCount;
//    protected final TreeReader[] fields;
//    private final String[] fieldNames;
//
//    protected StructTreeReader(
//        int columnId,
//        TreeReaderSchema treeReaderSchema,
//        boolean[] included,
//        boolean skipCorrupt) throws IOException {
//      super(columnId);
//
//      OrcProto.Type fileStructType = treeReaderSchema.getFileTypes().get(columnId);
//      fileColumnCount = fileStructType.getFieldNamesCount();
//
//      OrcProto.Type schemaStructType = treeReaderSchema.getSchemaTypes().get(columnId);
//
//      if (columnId == treeReaderSchema.getInnerStructSubtype()) {
//        // If there are more result columns than reader columns, we will default those additional
//        // columns to NULL.
//        resultColumnCount = schemaStructType.getFieldNamesCount();
//      } else {
//        resultColumnCount = fileColumnCount;
//      }
//
//      this.fields = new TreeReader[fileColumnCount];
//      this.fieldNames = new String[fileColumnCount];
//
//      if (included == null) {
//        for (int i = 0; i < fileColumnCount; ++i) {
//          int subtype = schemaStructType.getSubtypes(i);
//          this.fields[i] = createTreeReader(subtype, treeReaderSchema, included, skipCorrupt);
//          // Use the treeReaderSchema evolution name since file/reader types may not have the real column name.
//          this.fieldNames[i] = schemaStructType.getFieldNames(i);
//        }
//      } else {
//        for (int i = 0; i < fileColumnCount; ++i) {
//          int subtype = schemaStructType.getSubtypes(i);
//          if (subtype >= included.length) {
//            throw new IOException("subtype " + subtype + " exceeds the included array size " +
//                included.length + " fileTypes " + treeReaderSchema.getFileTypes().toString() +
//                " schemaTypes " + treeReaderSchema.getSchemaTypes().toString() +
//                " innerStructSubtype " + treeReaderSchema.getInnerStructSubtype());
//          }
//          if (included[subtype]) {
//            this.fields[i] = createTreeReader(subtype, treeReaderSchema, included, skipCorrupt);
//          }
//          // Use the treeReaderSchema evolution name since file/reader types may not have the real column name.
//          this.fieldNames[i] = schemaStructType.getFieldNames(i);
//        }
//      }
//    }
//
//    @Override
//    void seek(PositionProvider[] index) throws IOException {
//      super.seek(index);
//      for (TreeReader kid : fields) {
//        if (kid != null) {
//          kid.seek(index);
//        }
//      }
//    }
//
//    @Override
//    Object next(Object previous) throws IOException {
//      super.next(previous);
//      OrcStruct result = null;
//      if (valuePresent) {
//        if (previous == null) {
//          result = new OrcStruct(resultColumnCount);
//        } else {
//          result = (OrcStruct) previous;
//
//          // If the input format was initialized with a file with a
//          // different number of fields, the number of fields needs to
//          // be updated to the correct number
//          if (result.getNumFields() != resultColumnCount) {
//            result.setNumFields(resultColumnCount);
//          }
//        }
//        for (int i = 0; i < fileColumnCount; ++i) {
//          if (fields[i] != null) {
//            result.setFieldValue(i, fields[i].next(result.getFieldValue(i)));
//          }
//        }
//        if (resultColumnCount > fileColumnCount) {
//          for (int i = fileColumnCount; i < resultColumnCount; ++i) {
//            // Default new treeReaderSchema evolution fields to NULL.
//            result.setFieldValue(i, null);
//          }
//        }
//      }
//      return result;
//    }
//
//    @Override
//    public Object nextVector(Object previousVector, long batchSize) throws IOException {
//      final ColumnVector[] result;
//      if (previousVector == null) {
//        result = new ColumnVector[fileColumnCount];
//      } else {
//        result = (ColumnVector[]) previousVector;
//      }
//
//      // Read all the members of struct as column vectors
//      for (int i = 0; i < fileColumnCount; i++) {
//        if (fields[i] != null) {
//          if (result[i] == null) {
//            result[i] = (ColumnVector) fields[i].nextVector(null, batchSize);
//          } else {
//            fields[i].nextVector(result[i], batchSize);
//          }
//        }
//      }
//
//      // Default additional treeReaderSchema evolution fields to NULL.
//      if (vectorColumnCount != -1 && vectorColumnCount > fileColumnCount) {
//        for (int i = fileColumnCount; i < vectorColumnCount; ++i) {
//          ColumnVector colVector = result[i];
//          if (colVector != null) {
//            colVector.isRepeating = true;
//            colVector.noNulls = false;
//            colVector.isNull[0] = true;
//          }
//        }
//      }
//
//      return result;
//    }
//
//    @Override
//    void startStripe(Map<StreamName, InStream> streams,
//                     OrcProto.StripeFooter stripeFooter
//    ) throws IOException {
//      super.startStripe(streams, stripeFooter);
//      for (TreeReader field : fields) {
//        if (field != null) {
//          field.startStripe(streams, stripeFooter);
//        }
//      }
//    }
//
//    @Override
//    void skipRows(long items) throws IOException {
//      items = countNonNulls(items);
//      for (TreeReader field : fields) {
//        if (field != null) {
//          field.skipRows(items);
//        }
//      }
//    }
//  }

  public static TreeReader createTreeReader(int columnId,
                                            Column column,
                                            boolean skipCorrupt
  ) throws IOException {
    TypeDesc typeDesc = column.getTypeDesc();
    int orcColumnId = columnId + 1; // root record column is considered
    switch (typeDesc.getDataType().getType()) {
      case BOOLEAN:
        return new BooleanTreeReader(orcColumnId);
      case BIT:
        return new ByteTreeReader(orcColumnId);
      case FLOAT8:
        return new DoubleTreeReader(orcColumnId);
      case FLOAT4:
        return new FloatTreeReader(orcColumnId);
      case INT2:
        return new ShortTreeReader(orcColumnId);
      case INT4:
        return new IntTreeReader(orcColumnId);
      case INT8:
        return new LongTreeReader(orcColumnId, skipCorrupt);
      case TEXT:
        return new StringTreeReader(orcColumnId);
      case CHAR:
        return new CharTreeReader(orcColumnId, typeDesc.getDataType().getLength());
      case BLOB:
        return new BinaryTreeReader(orcColumnId);
      case TIMESTAMP:
        return new TimestampTreeReader(orcColumnId, skipCorrupt);
      case DATE:
        return new DateTreeReader(orcColumnId);
      case INET4:
        return new InetTreeReader(orcColumnId);
//      case STRUCT:
//        return new StructTreeReader(columnId, treeReaderSchema, included, skipCorrupt);
      default:
        throw new TajoRuntimeException(new UnsupportedException("Unsupported type " +
            typeDesc.getDataType().getType().name()));
    }
  }
}