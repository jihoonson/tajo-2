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

package org.apache.tajo.worker;

import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.engine.planner.LogicalNodeTree;
import org.apache.tajo.engine.planner.PhysicalPlanner;
import org.apache.tajo.engine.planner.PhysicalPlannerImpl;
import org.apache.tajo.engine.planner.logical.LogicalNode;
import org.apache.tajo.engine.planner.physical.PhysicalExec;
import org.apache.tajo.exception.InternalException;
import org.apache.tajo.storage.AbstractStorageManager;
import org.apache.tajo.storage.StorageManagerFactory;

import java.io.IOException;

public class TajoQueryEngine {

  private final AbstractStorageManager storageManager;
  private final PhysicalPlanner phyPlanner;

  public TajoQueryEngine(TajoConf conf) throws IOException {
    this.storageManager = StorageManagerFactory.getStorageManager(conf);
    this.phyPlanner = new PhysicalPlannerImpl(conf, storageManager);
  }
  
  public PhysicalExec createPlan(TaskAttemptContext ctx, LogicalNodeTree plan, LogicalNode root)
      throws InternalException {
    return phyPlanner.createPlan(ctx, plan, root);
  }
  
  public void stop() throws IOException {
  }
}
