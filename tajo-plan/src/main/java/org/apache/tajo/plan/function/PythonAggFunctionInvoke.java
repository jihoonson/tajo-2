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

package org.apache.tajo.plan.function;

import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.FunctionDesc;
import org.apache.tajo.common.TajoDataTypes;
import org.apache.tajo.datum.Datum;
import org.apache.tajo.datum.NullDatum;
import org.apache.tajo.datum.ProtobufDatum;
import org.apache.tajo.plan.function.python.PythonScriptEngine;
import org.apache.tajo.plan.serder.EvalNodeDeserializer;
import org.apache.tajo.plan.serder.EvalNodeSerializer;
import org.apache.tajo.plan.serder.PlanProto;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.storage.VTuple;

import java.io.IOException;

public class PythonAggFunctionInvoke extends AggFunctionInvoke implements Cloneable {

  private transient PythonScriptEngine scriptEngine;
  private transient PythonAggFunctionContext prevContext;
  private static int nextContextId = 0;

  public static class PythonAggFunctionContext implements FunctionContext {
    final int id;
    String jsonData;

    public PythonAggFunctionContext() {
      this.id = nextContextId++;
    }

    public void setJsonData(String jsonData) {
      this.jsonData = jsonData;
    }

    public String getJsonData() {
      return jsonData;
    }
  }

  public PythonAggFunctionInvoke(FunctionDesc functionDesc) {
    super(functionDesc);
  }

  @Override
  public void init(FunctionInvokeContext context) throws IOException {
    this.scriptEngine = (PythonScriptEngine) context.getScriptEngine();
  }

  @Override
  public FunctionContext newContext() {
    return new PythonAggFunctionContext();
  }

  private void updateContextIfNecessary(FunctionContext context) {
    PythonAggFunctionContext givenContext = (PythonAggFunctionContext) context;
    if (prevContext == null || prevContext.id != givenContext.id) {
      try {
        if (prevContext != null) {
          scriptEngine.updateJavaSideContext(prevContext);
        }
        scriptEngine.updatePythonSideContext(givenContext);
        prevContext = givenContext;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void eval(FunctionContext context, Tuple params) {
    updateContextIfNecessary(context);
    scriptEngine.callAggFunc(context, params);
  }

  @Override
  public void merge(FunctionContext context, Tuple params) {
    if (params.get(0) instanceof NullDatum) {
      return;
    }
    ProtobufDatum protobufDatum = (ProtobufDatum) params.get(0);
    PlanProto.Tuple namedTuple = (PlanProto.Tuple) protobufDatum.get();
    Tuple input = new VTuple(namedTuple.getDatumsCount());
    for (int i = 0; i < namedTuple.getDatumsCount(); i++) {
      input.put(i, EvalNodeDeserializer.deserialize(namedTuple.getDatums(i)));
    }

    updateContextIfNecessary(context);
    scriptEngine.callAggFunc(context, input);
  }

  @Override
  public Datum getPartialResult(FunctionContext context) {
    updateContextIfNecessary(context);
    Tuple intermResult = scriptEngine.getPartialResult(context);
    PlanProto.Tuple.Builder builder = PlanProto.Tuple.newBuilder();
    for (int i = 0; i < intermResult.size(); i++) {
      builder.addDatums(EvalNodeSerializer.serialize(intermResult.get(i)));
    }
    return new ProtobufDatum(builder.build());
  }

  @Override
  public TajoDataTypes.DataType getPartialResultType() {
    return CatalogUtil.newDataType(TajoDataTypes.Type.PROTOBUF, PlanProto.Tuple.class.getName());
  }

  @Override
  public Datum terminate(FunctionContext context) {
    updateContextIfNecessary(context);
    return scriptEngine.getFinalResult(context);
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    // nothing to do
    return super.clone();
  }
}
