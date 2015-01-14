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

package org.apache.tajo.plan.expr;


import com.google.common.collect.Sets;
import com.google.gson.annotations.Expose;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.Schema;
import org.apache.tajo.common.TajoDataTypes;
import org.apache.tajo.datum.Datum;
import org.apache.tajo.datum.DatumFactory;
import org.apache.tajo.datum.NullDatum;
import org.apache.tajo.storage.Tuple;

import java.util.Set;

public class InEval extends BinaryEval {
  private static final TajoDataTypes.DataType RES_TYPE = CatalogUtil.newSimpleDataType(TajoDataTypes.Type.BOOLEAN);

  @Expose private boolean not;
  Set<Datum> values;

  public InEval(EvalNode lhs, ValueSetEval valueList, boolean not) {
    super(EvalType.IN, lhs, valueList);
    this.not = not;
  }

  public boolean isNot() {
    return this.not;
  }

  @Override
  public TajoDataTypes.DataType getValueType() {
    return RES_TYPE;
  }

  @Override
  public String getName() {
    return "?";
  }

  @Override
  public Datum eval(Schema schema, Tuple tuple) {
    if (values == null) {
      values = Sets.newHashSet(((ValueSetEval)rightExpr).getValues());
    }

    Datum leftValue = leftExpr.eval(schema, tuple);

    if (leftValue.isNull()) {
      return NullDatum.get();
    }

    return DatumFactory.createBool(not ^ values.contains(leftValue));
  }

  public <T extends ValueSetEval> T getValueSet()  {
    return getRightExpr();
  }

  public EvalNode getPredicand() {
    return getLeftExpr();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof InEval) {
      InEval other = (InEval) obj;
      return super.equals(obj) && not == other.not;
    }
    return false;
  }

  public String toString() {
    return leftExpr + (not? " NOT" : "") + " IN (" + rightExpr + ")";
  }
}
