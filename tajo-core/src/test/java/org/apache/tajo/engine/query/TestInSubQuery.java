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

package org.apache.tajo.engine.query;

import org.apache.tajo.QueryTestCaseBase;
import org.apache.tajo.TajoConstants;
import org.junit.Test;

import java.sql.ResultSet;

public class TestInSubQuery extends QueryTestCaseBase {

  public TestInSubQuery() {
    super(TajoConstants.DEFAULT_DATABASE_NAME);
  }

  @Test
  public final void testInSubQuery() throws Exception {
    ResultSet res = executeQuery();
    assertResultSet(res);
    cleanupQuery(res);
  }

  @Test
  public final void testRecursiveInSubQuery() throws Exception {
    ResultSet res = executeQuery();
    assertResultSet(res);
    cleanupQuery(res);
  }

  @Test
  public final void testInSubQueryWithOtherConditions() throws Exception {
    ResultSet res = executeString("select n_name from nation where n_regionkey in (select r_regionkey from region) and n_nationkey > 1;");
    assertResultSet(res);
    cleanupQuery(res);
  }

  @Test
  public final void testMultipleInSubQuery() throws Exception {
    ResultSet res = executeString("select n_name from nation where n_regionkey in (select r_regionkey from region) and n_nationkey in (select s_nationkey from supplier);");
    assertResultSet(res);
    cleanupQuery(res);
  }

  @Test
  public final void testInSubQueryWithJoin() throws Exception {
    ResultSet res = executeString("select n_name from nation, supplier where n_regionkey in (select r_regionkey from region) and n_nationkey = s_nationkey;");
    assertResultSet(res);
    cleanupQuery(res);
  }

  @Test
  public final void testInSubQueryWithTableSubQuery() throws Exception {
    ResultSet res = executeString("select n_name from (select * from nation) as T where n_regionkey in (select r_regionkey from region);");
    assertResultSet(res);
    cleanupQuery(res);
  }

  @Test
  public final void testNotInSubQuery() throws Exception {
    ResultSet res = executeString("select n_name from nation where n_nationkey not in (select r_regionkey from region);");
    assertResultSet(res);
    cleanupQuery(res);
  }
}
