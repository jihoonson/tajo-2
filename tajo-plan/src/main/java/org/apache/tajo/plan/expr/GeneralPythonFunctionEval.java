///**
// * Licensed to the Apache Software Foundation (ASF) under one
// * or more contributor license agreements.  See the NOTICE file
// * distributed with this work for additional information
// * regarding copyright ownership.  The ASF licenses this file
// * to you under the Apache License, Version 2.0 (the
// * "License"); you may not use this file except in compliance
// * with the License.  You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package org.apache.tajo.plan.expr;
//
//import org.apache.tajo.catalog.FunctionDesc;
//import org.apache.tajo.catalog.Schema;
//import org.apache.tajo.common.TajoDataTypes;
//import org.apache.tajo.datum.Datum;
//import org.apache.tajo.function.PythonInvocationDesc;
//import org.apache.tajo.plan.function.python.JythonScriptEngine;
//import org.apache.tajo.plan.function.python.JythonUtils;
//import org.apache.tajo.storage.Tuple;
//import org.apache.tajo.storage.VTuple;
//import org.python.core.PyFunction;
//import org.python.core.PyObject;
//
//import java.io.IOException;
//
///**
// * Python implementation of a Tajo UDF performs mappings between Python & Tajo data structures.
// */
//public class GeneralPythonFunctionEval extends FunctionEval {
//  private Tuple params = null;
//
//  public GeneralPythonFunctionEval(FunctionDesc funcDesc, EvalNode[] argEvals) {
//    super(EvalType.PYTHON_FUNCTION, funcDesc, argEvals);
//  }
//
//  @Override
//  public Datum eval(Schema schema, Tuple tuple) {
//    if (this.params == null) {
//      params = new VTuple(argEvals.length);
//    }
//    if(argEvals != null) {
//      params.clear();
//      for(int i=0;i < argEvals.length; i++) {
//        params.put(i, argEvals[i].eval(schema, tuple));
//      }
//    }
//
//
//    PythonInvocationDesc invokeDesc = funcDesc.getInvocation().getPython();
//    try {
//      PyFunction function = JythonScriptEngine.getFunction(invokeDesc.getPath(), invokeDesc.getName());
//
//      TajoDataTypes.DataType[] paramTypes = funcDesc.getSignature().getParamTypes();
//      PyObject result;
//      if (paramTypes.length == 0) {
//        result = function.__call__();
//      } else {
//        // Find the actual data types from the given parameters at runtime,
//        // and convert them into PyObject instances.
//        PyObject[] pyParams = JythonUtils.tupleToPyTuple(params).getArray();
//        result = function.__call__(pyParams);
//      }
//
//      return JythonUtils.pyObjectToDatum(result);
//    } catch (IOException e) {
//      throw new RuntimeException(e);
//    }
//  }
//}