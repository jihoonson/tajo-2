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

package org.apache.tajo.plan.joinorder;

import com.google.common.collect.Sets;
import org.apache.tajo.algebra.JoinType;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.util.graph.SimpleUndirectedGraph;
import org.apache.tajo.plan.LogicalPlan;
import org.apache.tajo.plan.NamedExprsManager;
import org.apache.tajo.plan.util.PlannerUtil;
import org.apache.tajo.plan.PlanningException;
import org.apache.tajo.plan.expr.AlgebraicUtil;
import org.apache.tajo.plan.expr.BinaryEval;
import org.apache.tajo.plan.expr.EvalNode;
import org.apache.tajo.plan.expr.EvalTreeUtil;
import org.apache.tajo.plan.logical.JoinNode;
import org.apache.tajo.plan.logical.RelationNode;
import org.apache.tajo.util.TUtil;

import java.util.*;

/**
 * The join graph is a directed graph that comprises of logical node pids (vertices) and JoinEdges between them.
 * Every vertex must be unique even though they actually access the same data.
 * In addition, every vertex pair can have at most one edge.
 *
 * The edge direction represents the join direction.
 * That is, the tail (left) relation can be joinable with the head (right).
 */
public class JoinGraph extends SimpleUndirectedGraph<Integer, JoinEdge> {

  public void addJoin(JoinNode joinNode) throws PlanningException {
    JoinEdge edge = new JoinEdge(joinNode);
    addEdge(joinNode.getLeftChild().getPID(), joinNode.getRightChild().getPID(), edge);
  }

//  private String [] guessRelationsFromJoinQual(LogicalPlan.QueryBlock block, BinaryEval joinCondition)
//      throws PlanningException {
//
//    // Note that we can guarantee that each join qual used here is a singleton.
//    // This is because we use dissect a join qual into conjunctive normal forms.
//    // In other words, each join qual has a form 'col1 = col2'.
//    Column leftExpr = EvalTreeUtil.findAllColumnRefs(joinCondition.getLeftExpr()).get(0);
//    Column rightExpr = EvalTreeUtil.findAllColumnRefs(joinCondition.getRightExpr()).get(0);
//
//    // 0 - left table, 1 - right table
//    String [] relationNames = new String[2];
//
//    NamedExprsManager namedExprsMgr = block.getNamedExprsManager();
//    if (leftExpr.hasQualifier()) {
//      relationNames[0] = leftExpr.getQualifier();
//    } else {
//      if (namedExprsMgr.isAliasedName(leftExpr.getSimpleName())) {
//        String columnName = namedExprsMgr.getOriginalName(leftExpr.getSimpleName());
//        String qualifier = CatalogUtil.extractQualifier(columnName);
//        relationNames[0] = qualifier;
//      } else {
//        // search for a relation which evaluates a right term included in a join condition
//        for (RelationNode rel : block.getRelations()) {
//          if (rel.getOutSchema().contains(leftExpr)) {
//            String qualifier = rel.getCanonicalName();
//            relationNames[0] = qualifier;
//          }
//        }
//
//        if (relationNames[0] == null) { // if not found
//          throw new PlanningException("Cannot expect a referenced relation: " + leftExpr);
//        }
//      }
//    }
//
//    if (rightExpr.hasQualifier()) {
//      relationNames[1] = rightExpr.getQualifier();
//    } else {
//      if (namedExprsMgr.isAliasedName(rightExpr.getSimpleName())) {
//        String columnName = namedExprsMgr.getOriginalName(rightExpr.getSimpleName());
//        String qualifier = CatalogUtil.extractQualifier(columnName);
//        relationNames[1] = qualifier;
//      } else {
//        // search for a relation which evaluates a right term included in a join condition
//        for (RelationNode rel : block.getRelations()) {
//          if (rel.getOutSchema().contains(rightExpr)) {
//            String qualifier = rel.getCanonicalName();
//            relationNames[1] = qualifier;
//          }
//        }
//
//        if (relationNames[1] == null) { // if not found
//          throw new PlanningException("Cannot expect a referenced relation: " + rightExpr);
//        }
//      }
//    }
//
//    return relationNames;
//  }

//  public Collection<EvalNode> addJoin(LogicalPlan plan, LogicalPlan.QueryBlock block,
//                                      JoinNode joinNode) throws PlanningException {
//    if (joinNode.getJoinType() == JoinType.LEFT_OUTER || joinNode.getJoinType() == JoinType.RIGHT_OUTER) {
//      JoinEdge edge = new JoinEdge(joinNode);
//
//      SortedSet<String> leftNodeRelationName =
//          new TreeSet<String>(PlannerUtil.getRelationLineageWithinQueryBlock(plan, joinNode.getLeftChild()));
//      SortedSet<String> rightNodeRelationName =
//          new TreeSet<String>(PlannerUtil.getRelationLineageWithinQueryBlock(plan, joinNode.getRightChild()));
//
//      addEdge(
//          TUtil.collectionToString(leftNodeRelationName, ","),
//          TUtil.collectionToString(rightNodeRelationName, ","),
//          edge);
//
//      Set<EvalNode> allInOneCnf = new HashSet<EvalNode>();
//      allInOneCnf.add(joinNode.getJoinQual());
//
//      return allInOneCnf;
//    } else if (joinNode.getJoinType() == JoinType.CROSS) {
//      JoinEdge edge = new JoinEdge(joinNode);
//      SortedSet<String> leftNodeRelationName =
//          new TreeSet<String>(PlannerUtil.getRelationLineageWithinQueryBlock(plan, joinNode.getLeftChild()));
//      SortedSet<String> rightNodeRelationName =
//          new TreeSet<String>(PlannerUtil.getRelationLineageWithinQueryBlock(plan, joinNode.getRightChild()));
//
//      addEdge(
//          TUtil.collectionToString(leftNodeRelationName, ","),
//          TUtil.collectionToString(rightNodeRelationName, ","),
//          edge);
//      return new HashSet<EvalNode>();
//    } else {
//      Collection<String> leftNodeRelationName =
//          PlannerUtil.getRelationLineageWithinQueryBlock(plan, joinNode.getLeftChild());
//      Collection<String> rightNodeRelationName =
//          PlannerUtil.getRelationLineageWithinQueryBlock(plan, joinNode.getRightChild());
//      String leftRelationLineageName = TUtil.collectionToString(leftNodeRelationName, ",");
//      String rightRelationLineageName = TUtil.collectionToString(rightNodeRelationName, ",");
//      JoinEdge edge = getEdge(leftRelationLineageName, rightRelationLineageName);
//      if (edge == null) {
//        edge = new JoinEdge(joinNode);
//        addEdge(leftRelationLineageName, rightRelationLineageName, edge);
//      }
//
//      Set<EvalNode> cnf = Sets.newHashSet(AlgebraicUtil.toConjunctiveNormalFormArray(joinNode.getJoinQual()));
//      for (EvalNode singleQual : cnf) {
//        if (EvalTreeUtil.isJoinQual(block,
//            joinNode.getLeftChild().getOutSchema(),
//            joinNode.getRightChild().getOutSchema(),
//            singleQual, true)) {
//          edge.addJoinQual(singleQual);
//        }
//      }
//      return cnf;
//    }
//  }
}
