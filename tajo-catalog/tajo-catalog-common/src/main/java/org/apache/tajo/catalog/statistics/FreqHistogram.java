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

package org.apache.tajo.catalog.statistics;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.proto.CatalogProtos.FreqBucketProto;
import org.apache.tajo.catalog.proto.CatalogProtos.FreqHistogramProto;
import org.apache.tajo.catalog.statistics.FreqHistogram.Bucket;
import org.apache.tajo.common.ProtoObject;
import org.apache.tajo.datum.DatumFactory;
import org.apache.tajo.datum.NullDatum;
import org.apache.tajo.json.GsonObject;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.storage.VTuple;
import org.apache.tajo.util.TUtil;

import java.math.BigDecimal;
import java.util.*;

/**
 * Frequency histogram
 */
public class FreqHistogram extends Histogram<TupleRange, Bucket>
    implements ProtoObject<FreqHistogramProto>, Cloneable, GsonObject {

  private final TupleComparator comparator;
  private final SortSpec[] sortSpecs;
  private final Map<TupleRange, Bucket> buckets = new HashMap<>();
  private final TupleComparator intervalComparator;
  private Tuple minInterval;

  public FreqHistogram(SortSpec[] sortSpecs) {
    Schema keySchema = HistogramUtil.sortSpecsToSchema(sortSpecs);
    this.sortSpecs = sortSpecs;
    this.comparator = new BaseTupleComparator(keySchema, sortSpecs);
    SortSpec[] baseSortSpecs = new SortSpec[keySchema.size()];
    for (int i = 0; i < keySchema.size(); i++) {
      baseSortSpecs[i] = new SortSpec(keySchema.getColumn(i), true, sortSpecs[i].isNullFirst());
    }
    this.intervalComparator = new BaseTupleComparator(keySchema, baseSortSpecs);
  }

  public FreqHistogram(SortSpec[] sortSpec, Collection<Bucket> buckets) {
    this(sortSpec);
    for (Bucket bucket : buckets) {
      this.buckets.put(bucket.key, bucket);
    }
  }

  public FreqHistogram(FreqHistogramProto proto) {
    SortSpec[] sortSpecs = new SortSpec[proto.getSortSpecCount()];
    for (int i = 0; i < sortSpecs.length; i++) {
      sortSpecs[i] = new SortSpec(proto.getSortSpec(i));
    }
    Schema keySchema = HistogramUtil.sortSpecsToSchema(sortSpecs);
    this.sortSpecs = sortSpecs;
    this.comparator = new BaseTupleComparator(keySchema, sortSpecs);
    for (FreqBucketProto eachBucketProto : proto.getBucketsList()) {
      Bucket bucket = new Bucket(eachBucketProto);
      buckets.put(bucket.key, bucket);
    }
    SortSpec[] baseSortSpecs = new SortSpec[keySchema.size()];
    for (int i = 0; i < keySchema.size(); i++) {
      baseSortSpecs[i] = new SortSpec(keySchema.getColumn(i), true, sortSpecs[i].isNullFirst());
    }
    this.intervalComparator = new BaseTupleComparator(keySchema, baseSortSpecs);
  }

  public Tuple getNonZeroMinInterval(AnalyzedSortSpec[] sortSpecs) {
    if (this.minInterval == null) {
//      Tuple zeroTuple = TupleRangeUtil.createMinBaseTuple(context.sortSpecs);
      for (Bucket eachBucket : buckets.values()) {
        if (minInterval == null) {
          minInterval = eachBucket.getBase();
        } else if (!HistogramUtil.normalizeTupleAsVector(sortSpecs, eachBucket.getBase()).equals(BigDecimal.ZERO)
            && intervalComparator.compare(minInterval, eachBucket.getBase()) > 0) {
          minInterval = eachBucket.getBase();
        }
      }
    }
    return minInterval;
  }

  public SortSpec[] getSortSpecs() {
    return sortSpecs;
  }

  public void updateBucket(Tuple startKey, Tuple endKey, Tuple base, long change) {
    // TODO: normalize length
    Tuple startClone, endClone, baseClone;
    try {
      startClone = startKey.clone();
      endClone = endKey.clone();
      baseClone = base.clone();
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
    TupleRange key = new TupleRange(startClone, endClone, baseClone, comparator);
    updateBucket(key, change);
    minInterval = null;
  }

  /**
   *
   * @param key
   * @param change
   */
  @Override
  public void updateBucket(TupleRange key, long change) {
    if (buckets.containsKey(key)) {
      getBucket(key).incCount(change);
    } else {
      buckets.put(key, new Bucket(key, change));
    }
  }

  public void updateBucket(TupleRange key, long change, boolean endKeyInclusive) {
    updateBucket(key, change);
    buckets.get(key).setEndKeyInclusive(endKeyInclusive);
  }

  /**
   *
   * @param other
   */
  public void merge(AnalyzedSortSpec[] analyzedSpecs, FreqHistogram other) {
    // Find the min interval from both histograms
    Tuple minInterval;
    if (this.size() > 0 && other.size() > 0) {
      minInterval = comparator.compare(this.getNonZeroMinInterval(analyzedSpecs),
          other.getNonZeroMinInterval(analyzedSpecs)) > 0 ?
          other.getNonZeroMinInterval(analyzedSpecs) : this.getNonZeroMinInterval(analyzedSpecs);
    } else if (this.size() > 0) {
      minInterval = this.getNonZeroMinInterval(analyzedSpecs);
    } else if (other.size() > 0) {
      minInterval = other.getNonZeroMinInterval(analyzedSpecs);
    } else {
      return;
    }

    // Split buckets with the min interval
    this.buckets.clear();
    for (Bucket eachBucket : this.buckets.values()) {
      if (comparator.compare(eachBucket.getBase(), minInterval) > 0) {
        // Split the bucket
        for (Bucket split : HistogramUtil.splitBucket(this, analyzedSpecs, eachBucket, minInterval)) {
          this.buckets.put(split.key, split);
        }
      } else {
        this.buckets.put(eachBucket.key, eachBucket);
      }
    }

    for (Bucket eachBucket: other.buckets.values()) {
      if (comparator.compare(eachBucket.getBase(), minInterval) > 0) {
        // Split the bucket
        for (Bucket split : HistogramUtil.splitBucket(this, analyzedSpecs, eachBucket, minInterval)) {
          this.buckets.put(split.key, split);
        }
      } else {
        this.buckets.put(eachBucket.key, eachBucket);
      }
    }

    this.minInterval = other.minInterval = minInterval;
  }

  public Bucket getBucket(Tuple startKey, Tuple endKey, Tuple base) {
    return getBucket(new TupleRange(startKey, endKey, base, comparator));
  }

  @Override
  public Bucket getBucket(TupleRange key) {
    return buckets.get(key);
  }

  public Collection<Bucket> getAllBuckets() {
    return Collections.unmodifiableCollection(buckets.values());
  }

  public List<Bucket> getSortedBuckets() {
    List<Bucket> bucketList = new ArrayList<>(this.buckets.values());
    bucketList.sort(new Comparator<Bucket>() {
      @Override
      public int compare(Bucket o1, Bucket o2) {
        return o1.getKey().compareTo(o2.getKey());
      }
    });
    return bucketList;
  }

  public int size() {
    return buckets.size();
  }

  public Comparator<Tuple> getComparator() {
    return comparator;
  }

  @Override
  public String toJson() {
    return null;
  }

  @Override
  public FreqHistogramProto getProto() {
    FreqHistogramProto.Builder builder = FreqHistogramProto.newBuilder();
    for (SortSpec sortSpec : sortSpecs) {
      builder.addSortSpec(sortSpec.getProto());
    }
    for (Bucket bucket : buckets.values()) {
      builder.addBuckets(bucket.getProto());
    }
    return builder.build();
  }

  public void clear() {
    buckets.clear();
  }

  public Bucket createBucket(TupleRange key, long count) {
    return new Bucket(key, count);
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof FreqHistogram) {
      FreqHistogram other = (FreqHistogram) o;
      boolean eq = Arrays.equals(this.sortSpecs, other.sortSpecs);
      eq &= this.getSortedBuckets().equals(other.getSortedBuckets());
      return eq;
    }
    return false;
  }

  public class Bucket implements ProtoObject<FreqBucketProto>, Cloneable, GsonObject {
    // [start key, end key)
    private final TupleRange key;
    private long count;
    private boolean endKeyInclusive = false; // set for only the last bucket

    public Bucket(TupleRange key) {
      this(key, 0);
    }

    public Bucket(TupleRange key, long count) {
      this.key = key;
      this.count = count;
    }

    public Bucket(FreqBucketProto proto) {
      Tuple startKey = new VTuple(sortSpecs.length);
      Tuple endKey = new VTuple(sortSpecs.length);
      Tuple base = new VTuple(sortSpecs.length);
      for (int i = 0; i < sortSpecs.length; i++) {
        startKey.put(i, proto.getStartKey(i).size() == 0 ? NullDatum.get() :
            DatumFactory.createFromBytes(sortSpecs[i].getSortKey().getDataType(),
            proto.getStartKey(i).toByteArray()));
        endKey.put(i, proto.getEndKey(i).size() == 0 ? NullDatum.get() :
            DatumFactory.createFromBytes(sortSpecs[i].getSortKey().getDataType(),
            proto.getEndKey(i).toByteArray()));
        base.put(i, proto.getBase(i).size() == 0 ? NullDatum.get() :
            DatumFactory.createFromBytes(sortSpecs[i].getSortKey().getDataType(),
            proto.getBase(i).toByteArray()));
      }
      this.key = new TupleRange(startKey, endKey, base, comparator);
      this.count = proto.getCount();
    }

    @Override
    public FreqBucketProto getProto() {
      FreqBucketProto.Builder builder = FreqBucketProto.newBuilder();
      for (int i = 0; i < sortSpecs.length; i++) {
        builder.addStartKey(ByteString.copyFrom(key.getStart().asDatum(i).asByteArray()));
        builder.addEndKey(ByteString.copyFrom(key.getEnd().asDatum(i).asByteArray()));
        builder.addBase(ByteString.copyFrom(key.getBase().asDatum(i).asByteArray()));
      }
      return builder.setCount(count).build();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
      return super.clone();
    }

    @Override
    public String toJson() {
      return null;
    }

    public TupleRange getKey() {
      return key;
    }

    public Tuple getBase() {
      return key.getBase();
    }

    public Tuple getStartKey() {
      return key.getStart();
    }

    public Tuple getEndKey() {
      return key.getEnd();
    }

    public long getCount() {
      return count;
    }

    public void setCount(long count) {
      this.count = count;
    }

    public void incCount(long inc) {
      this.count += inc;
    }

    public void merge(AnalyzedSortSpec[] sortSpecs, Bucket other) {
      Tuple minStart, maxEnd;
      if (this.key.compareTo(other.key) < 0) {
        minStart = key.getStart();
        maxEnd = other.key.getEnd();
      } else {
        minStart = other.key.getStart();
        maxEnd = key.getEnd();
      }

      this.key.setStart(minStart);
      this.key.setEnd(maxEnd);
      if (!this.key.getBase().equals(other.key.getBase())) {
        this.key.setBase(getMeanInterval(sortSpecs, this.key.getBase(), other.key.getBase()));
      }
      this.count += other.count;
    }

    @VisibleForTesting
    public Tuple getMeanInterval(AnalyzedSortSpec[] sortSpecs, Tuple interval1, Tuple interval2) {
      BigDecimal[] n1 = HistogramUtil.normalizeTupleAsVector(sortSpecs, interval1);
      BigDecimal[] n2 = HistogramUtil.normalizeTupleAsVector(sortSpecs, interval2);
      int[] scales = HistogramUtil.maxScales(n1, n2);
      BigDecimal s1 = HistogramUtil.weightedSum(n1, scales);
      BigDecimal s2 = HistogramUtil.weightedSum(n2, scales);
      BigDecimal mean = s1.add(s2).divide(BigDecimal.valueOf(2), HistogramUtil.DECIMAL128_HALF_UP);
      BigDecimal[] normMeanInterval = HistogramUtil.normTupleFromWeightedSum(sortSpecs, mean, scales);
      return HistogramUtil.denormalizeAsVector(sortSpecs, normMeanInterval);
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Bucket) {
        Bucket other = (Bucket) o;
        return TUtil.checkEquals(this.key, other.key) &&
            this.count == other.count;
      }
      return false;
    }

    @Override
    public String toString() {
      return key + " (" + count + ")";
    }

    public boolean isEndKeyInclusive() {
      return endKeyInclusive;
    }

    public void setEndKeyInclusive(boolean endKeyInclusive) {
      this.endKeyInclusive = endKeyInclusive;
    }
  }
}
