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

package org.apache.tajo.engine.planner.physical;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.plan.Target;
import org.apache.tajo.plan.expr.AggregationFunctionCallEval;
import org.apache.tajo.plan.function.FunctionContext;
import org.apache.tajo.plan.logical.DistinctGroupbyNode;
import org.apache.tajo.plan.logical.GroupbyNode;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.worker.TaskAttemptContext;

import java.io.IOException;
import java.util.*;

/**
 *  This class aggregates the output of DistinctGroupbySecondAggregationExec.
 *
 */
public class DistinctGroupbyThirdAggregationExec extends UnaryPhysicalExec {
  private final static Log LOG = LogFactory.getLog(DistinctGroupbyThirdAggregationExec.class);
  private final DistinctGroupbyNode plan;

  private boolean finished = false;

  private DistinctFinalAggregator[] aggregators;
  private DistinctFinalAggregator nonDistinctAggr;

  private int resultTupleLength;
  private int numGroupingColumns;

  private int[] resultTupleIndexes;
  private Tuple emptyTuple;

  public DistinctGroupbyThirdAggregationExec(TaskAttemptContext context, DistinctGroupbyNode plan, SortExec sortExec)
      throws IOException {
    super(context, plan.getInSchema(), plan.getOutSchema(), sortExec);
    this.plan = plan;
  }

  @Override
  public void init() throws IOException {
    super.init();

    numGroupingColumns = plan.getGroupingColumns().length;
    resultTupleLength = numGroupingColumns;

    List<GroupbyNode> groupbyNodes = plan.getSubPlans();

    List<DistinctFinalAggregator> aggregatorList = new ArrayList<DistinctFinalAggregator>();
    int inTupleIndex = 1 + numGroupingColumns;
    int outTupleIndex = numGroupingColumns;
    int distinctSeq = 0;

    for (GroupbyNode eachGroupby : groupbyNodes) {
      if (eachGroupby.isDistinct()) {
        aggregatorList.add(new DistinctFinalAggregator(distinctSeq, inTupleIndex, outTupleIndex, eachGroupby));
        distinctSeq++;

        Column[] distinctGroupingColumns = eachGroupby.getGroupingColumns();
        inTupleIndex += distinctGroupingColumns.length;
        outTupleIndex += eachGroupby.getAggFunctions().length;
      } else {
        nonDistinctAggr = new DistinctFinalAggregator(-1, inTupleIndex, outTupleIndex, eachGroupby);
        outTupleIndex += eachGroupby.getAggFunctions().length;
      }
      resultTupleLength += eachGroupby.getAggFunctions().length;
    }
    aggregators = aggregatorList.toArray(new DistinctFinalAggregator[]{});

    // make output schema mapping index
    resultTupleIndexes = new int[outSchema.size()];
    Map<Column, Integer> groupbyResultTupleIndex = new HashMap<Column, Integer>();
    int resultTupleIndex = 0;
    for (Column eachColumn: plan.getGroupingColumns()) {
      groupbyResultTupleIndex.put(eachColumn, resultTupleIndex);
      resultTupleIndex++;
    }
    for (GroupbyNode eachGroupby : groupbyNodes) {
      Set<Column> groupingColumnSet = new HashSet<Column>();
      for (Column column: eachGroupby.getGroupingColumns()) {
        groupingColumnSet.add(column);
      }
      for (Target eachTarget: eachGroupby.getTargets()) {
        if (!groupingColumnSet.contains(eachTarget.getNamedColumn())) {
          //aggr function
          groupbyResultTupleIndex.put(eachTarget.getNamedColumn(), resultTupleIndex);
          resultTupleIndex++;
        }
      }
    }

    int index = 0;
    for (Column eachOutputColumn: outSchema.getRootColumns()) {
      // If column is avg aggregation function, outschema's column type is float
      // but groupbyResultTupleIndex's column type is protobuf

      int matchedIndex = -1;
      for (Map.Entry<Column, Integer> entry: groupbyResultTupleIndex.entrySet()) {
        if (entry.getKey().getQualifiedName().equals(eachOutputColumn.getQualifiedName())) {
          matchedIndex = entry.getValue();
          break;
        }
      }
      if (matchedIndex < 0) {
        throw new IOException("Can't find proper output column mapping: " + eachOutputColumn);
      }
      resultTupleIndexes[matchedIndex] = index++;
    }
  }

  Tuple prevKeyTuple = null;
  Tuple prevTuple = null;
  Tuple curKeyTuple = null;
  Tuple resultTuple = null;

  @Override
  public Tuple next() throws IOException {
    if (finished) {
      return null;
    }

    if (resultTuple == null) {
      resultTuple = createEmptyTuple(resultTupleLength);
    } else {
      resultTuple.clear();
    }

    while (!context.isStopped()) {
      Tuple tuple = child.next();
      // Last tuple
      if (tuple == null) {
        finished = true;

        if (prevTuple == null) {
          // Empty case
          if (numGroupingColumns == 0) {
            // No grouping column, return null tuple
            return makeEmptyTuple();
          } else {
            return null;
          }
        }

        for (int i = 0; i < numGroupingColumns; i++) {
          resultTuple.put(resultTupleIndexes[i], prevTuple.asDatum(i + 1));
        }
        for (DistinctFinalAggregator eachAggr: aggregators) {
          eachAggr.terminate(resultTuple);
        }
        break;
      }

      if (curKeyTuple == null) {
        curKeyTuple = createEmptyTuple(numGroupingColumns);
      }

      int distinctSeq = tuple.getInt2(0);
      curKeyTuple = getGroupingKeyTuple(tuple);

      // First tuple
      if (prevKeyTuple == null) {
        prevKeyTuple = curKeyTuple;
        prevTuple = tuple;

        aggregators[distinctSeq].merge(tuple);
        continue;
      }

      if (!prevKeyTuple.equals(curKeyTuple)) {
        // new grouping key
        for (int i = 0; i < numGroupingColumns; i++) {
          resultTuple.put(resultTupleIndexes[i], prevTuple.asDatum(i + 1));
        }
        for (DistinctFinalAggregator eachAggr: aggregators) {
          eachAggr.terminate(resultTuple);
        }

        prevKeyTuple = curKeyTuple;
        prevTuple = tuple;

        aggregators[distinctSeq].merge(tuple);
        break;
      } else {
        prevKeyTuple = curKeyTuple;
        prevTuple = tuple;
        aggregators[distinctSeq].merge(tuple);
      }
    }

    return resultTuple;
  }

  private Tuple makeEmptyTuple() {
    if (emptyTuple == null) {
      emptyTuple = createEmptyTuple(resultTupleLength);
    } else {
      emptyTuple.clear();
    }
    for (DistinctFinalAggregator eachAggr: aggregators) {
      eachAggr.terminateEmpty(resultTuple);
    }

    return resultTuple;
  }

  private Tuple getGroupingKeyTuple(Tuple tuple) {
    for (int i = 0; i < numGroupingColumns; i++) {
      curKeyTuple.put(i, tuple.asDatum(i + 1));
    }

    return curKeyTuple;
  }

  @Override
  public void rescan() throws IOException {
    super.rescan();
    prevKeyTuple = null;
    prevTuple = null;
    finished = false;
  }

  @Override
  public void close() throws IOException {
    super.close();
  }

  class DistinctFinalAggregator {
    private FunctionContext[] functionContexts;
    private AggregationFunctionCallEval[] aggrFunctions;
    private int seq;
    private int inTupleIndex;
    private int outTupleIndex;
    public DistinctFinalAggregator(int seq, int inTupleIndex, int outTupleIndex, GroupbyNode groupbyNode) {
      this.seq = seq;
      this.inTupleIndex = inTupleIndex;
      this.outTupleIndex = outTupleIndex;

      aggrFunctions = groupbyNode.getAggFunctions();
      if (aggrFunctions != null) {
        for (AggregationFunctionCallEval eachFunction: aggrFunctions) {
          eachFunction.bind(context.getEvalContext(), inSchema);
          eachFunction.setLastPhase();
        }
      }
      newFunctionContext();
    }

    private void newFunctionContext() {
      functionContexts = new FunctionContext[aggrFunctions.length];
      for (int i = 0; i < aggrFunctions.length; i++) {
        functionContexts[i] = aggrFunctions[i].newContext();
      }
    }

    public void merge(Tuple tuple) {
      for (int i = 0; i < aggrFunctions.length; i++) {
        aggrFunctions[i].merge(functionContexts[i], tuple);
      }

      if (seq == 0 && nonDistinctAggr != null) {
        if (!tuple.isBlankOrNull(nonDistinctAggr.inTupleIndex)) {
          nonDistinctAggr.merge(tuple);
        }
      }
    }

    public void terminate(Tuple resultTuple) {
      for (int i = 0; i < aggrFunctions.length; i++) {
        resultTuple.put(resultTupleIndexes[outTupleIndex + i], aggrFunctions[i].terminate(functionContexts[i]));
      }
      newFunctionContext();

      if (seq == 0 && nonDistinctAggr != null) {
        nonDistinctAggr.terminate(resultTuple);
      }
    }

    public void terminateEmpty(Tuple resultTuple) {
      newFunctionContext();
      for (int i = 0; i < aggrFunctions.length; i++) {
        resultTuple.put(resultTupleIndexes[outTupleIndex + i], aggrFunctions[i].terminate(functionContexts[i]));
      }
      if (seq == 0 && nonDistinctAggr != null) {
        nonDistinctAggr.terminateEmpty(resultTuple);
      }
    }
  }
}
