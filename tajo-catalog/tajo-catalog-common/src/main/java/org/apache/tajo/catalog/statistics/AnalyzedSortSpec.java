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

import org.apache.tajo.catalog.Column;
import org.apache.tajo.catalog.SortSpec;
import org.apache.tajo.common.TajoDataTypes.Type;
import org.apache.tajo.datum.Datum;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class AnalyzedSortSpec {

  private final SortSpec sortSpec;
  private Datum minValue;
  private Datum maxValue;

  private double minInterval = 1;

  // Below variables are used only for text type
  private boolean hasNullValue;
  private boolean isPureAscii;
  private int maxLength;

  private BigDecimal min;
  private BigDecimal max; // exclusive
  private BigDecimal normMin; // (min / max)
  private BigDecimal transMax; // max - min
//  private BigDecimal normTransMax; // (max - min) / max
  private BigDecimal normMinInterval;

  public AnalyzedSortSpec(SortSpec sortSpec) {
    this.sortSpec = sortSpec;
  }

  public AnalyzedSortSpec(SortSpec sortSpec, ColumnStats columnStats) {
    this(sortSpec);
    this.minValue = columnStats.getMinValue();
    this.maxValue = columnStats.getMaxValue();
  }

  public AnalyzedSortSpec(SortSpec sortSpec, ColumnStats columnStats, boolean isPureAscii, int maxLength) {
    this(sortSpec, columnStats);
    this.hasNullValue = columnStats.hasNullValue();
    this.isPureAscii = isPureAscii;
    this.maxLength = maxLength;
  }

  public SortSpec getSortSpec() {
    return sortSpec;
  }

  public Column getSortKey() {
    return sortSpec.getSortKey();
  }

  public Type getType() {
    return sortSpec.getSortKey().getDataType().getType();
  }

  public boolean isAscending() {
    return sortSpec.isAscending();
  }

  public boolean isNullsFirst() {
    return sortSpec.isNullsFirst();
  }

  public Datum getMinValue() {
    return minValue;
  }

  public void setMinValue(Datum minValue) {
    this.minValue = minValue;
  }

  public Datum getMaxValue() {
    return maxValue;
  }

  public void setMaxValue(Datum maxValue) {
    this.maxValue = maxValue;
  }

  public Boolean isPureAscii() {
    return isPureAscii;
  }

  public void setPureAscii(Boolean pureAscii) {
    isPureAscii = pureAscii;
  }

  public Integer getMaxLength() {
    return maxLength;
  }

  public void setMaxLength(Integer maxLength) {
    this.maxLength = maxLength;
  }

  public boolean hasNullValue() {
    return hasNullValue;
  }

  public void setHasNullValue(boolean hasNullValue) {
    this.hasNullValue = hasNullValue;
  }

  public void setMinInterval(double minInterval) {
    this.minInterval = minInterval;
  }

  public double getMinInterval() {
    return minInterval;
  }

  public BigDecimal getMax() {
    prepareMinMax();
    return max;
  }

  public BigDecimal getMin() {
    prepareMinMax();
    return min;
  }

  public BigDecimal getNormMin() {
    prepareMinMax();
    return normMin;
  }

  public BigDecimal getTransMin() {
    return BigDecimal.ZERO;
  }

  public BigDecimal getTransMax() {
    prepareMinMax();
    return transMax;
  }

  public BigDecimal getNormTransMin() {
    return BigDecimal.ZERO;
  }

  public BigDecimal getNormTransMax() {
//    prepareMinMax();
//    return normTransMax;
    return BigDecimal.ONE;
  }

  public BigDecimal getNormMinInterval() {
    return normMinInterval;
  }

  private void prepareMinMax() {
    if (min == null) {
      BigDecimal[] minMax = HistogramUtil.getMinMaxIncludeNull(this);
      this.min = minMax[0];
      this.max = minMax[1];
      this.normMin = min.divide(max, 128, BigDecimal.ROUND_HALF_UP);
      this.transMax = max.subtract(min);
//      this.normTransMax = transMax.divide(transMax, 128, BigDecimal.ROUND_HALF_UP);
      this.normMinInterval = BigDecimal.valueOf(minInterval).divide(transMax, 128, RoundingMode.HALF_UP);
    }
  }

  @Override
  public String toString() {
//    private Datum minValue;
//    private Datum maxValue;
//
//    private double minInterval = 1;
//
//    // Below variables are used only for text type
//    private boolean hasNullValue;
//    private boolean isPureAscii;
//    private int maxLength;
    return sortSpec
        + ", min: " + minValue
        + ", max: " + maxValue
        + ", minInterval: " + minInterval
        + ", hasNullValue: " + hasNullValue
        + ", isPureAscii: " + isPureAscii
        + ", maxLength: " + maxLength;
  }
}