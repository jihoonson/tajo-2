/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.plan.joinorder;

import org.apache.tajo.plan.LogicalPlan;
import org.apache.tajo.plan.expr.EvalNode;
import org.apache.tajo.plan.util.PlannerUtil;
import org.apache.tajo.plan.PlanningException;
import org.apache.tajo.plan.expr.AlgebraicUtil;
import org.apache.tajo.plan.logical.*;
import org.apache.tajo.util.TUtil;

import java.util.*;

/**
 * This is a greedy heuristic algorithm to find a bushy join tree. This algorithm finds
 * the best join order with join conditions and pushed-down join conditions to
 * all join operators.
 */
public class GreedyHeuristicJoinOrderAlgorithm implements JoinOrderAlgorithm {
  public static double DEFAULT_SELECTION_FACTOR = 0.1;

  @Override
  public FoundJoinOrder findBestOrder(LogicalPlan plan, LogicalPlan.QueryBlock block, JoinGraphContext graphContext)
      throws PlanningException {

    Set<JoinVertex> vertexes = TUtil.newHashSet();
    for (RelationNode relationNode : block.getRelations()) {
      vertexes.add(new RelationVertex(relationNode));
    }
    JoinEdgeFinderContext context = new JoinEdgeFinderContext();
    JoinGraph joinGraph = graphContext.getJoinGraph();
    while (vertexes.size() > 1) {
      JoinEdge bestPair = getBestPair(context, graphContext, vertexes);
      JoinedRelationsVertex newVertex = new JoinedRelationsVertex(bestPair);

      if (bestPair.getLeftVertex().equals(graphContext.getMostLeftVertex())) {
        graphContext.setMostLeftVertex(newVertex);
      }

      Set<JoinEdge> willBeRemoved = TUtil.newHashSet();
      Set<JoinEdge> willBeAdded = TUtil.newHashSet();

      prepareGraphUpdate(plan, block, joinGraph.getOutgoingEdges(bestPair.getLeftVertex()), newVertex, true,
          willBeAdded, willBeRemoved);

      prepareGraphUpdate(plan, block, joinGraph.getIncomingEdges(bestPair.getLeftVertex()), newVertex, false,
          willBeAdded, willBeRemoved);

      prepareGraphUpdate(plan, block, joinGraph.getOutgoingEdges(bestPair.getRightVertex()), newVertex, true,
          willBeAdded, willBeRemoved);

      prepareGraphUpdate(plan, block, joinGraph.getIncomingEdges(bestPair.getRightVertex()), newVertex, false,
          willBeAdded, willBeRemoved);

      for (JoinEdge edge : willBeRemoved) {
        joinGraph.removeEdge(edge.getLeftVertex(), edge.getRightVertex());
      }

      for (JoinEdge edge : willBeAdded) {
        joinGraph.addEdge(edge.getLeftVertex(), edge.getRightVertex(), edge);
      }

      vertexes.remove(bestPair.getLeftVertex());
      vertexes.remove(bestPair.getRightVertex());
      vertexes.add(newVertex);
    }

    JoinNode joinTree = (JoinNode) vertexes.iterator().next().getCorrespondingNode();
//    // all generated nodes should be registered to corresponding blocks
    block.registerNode(joinTree);
    return new FoundJoinOrder(joinTree, getCost(joinTree));

//    // Setup a remain relation set to be joined
//    // Why we should use LinkedHashSet? - it should keep the deterministic for the order of joins.
//    // Otherwise, join orders can be different even if join costs are the same to each other.
//    Set<LogicalNode> remainRelations = new LinkedHashSet<LogicalNode>();
//    for (RelationNode relation : block.getRelations()) {
//      remainRelations.add(relation);
//    }
//
//    LogicalNode latestJoin;
//    JoinEdge bestPair;
//
//    while (remainRelations.size() > 1) {
//      Set<LogicalNode> checkingRelations = new LinkedHashSet<LogicalNode>();
//
//      for (LogicalNode relation : remainRelations) {
//        Collection <String> relationStrings = PlannerUtil.getRelationLineageWithinQueryBlock(plan, relation);
//        List<JoinEdge> joinEdges = new ArrayList<JoinEdge>();
//        String relationCollection = TUtil.collectionToString(relationStrings, ",");
//        List<JoinEdge> joinEdgesForGiven = joinGraph.getIncomingEdges(relationCollection);
//        if (joinEdgesForGiven != null) {
//          joinEdges.addAll(joinEdgesForGiven);
//        }
//        if (relationStrings.size() > 1) {
//          for (String relationString: relationStrings) {
//            joinEdgesForGiven = joinGraph.getIncomingEdges(relationString);
//            if (joinEdgesForGiven != null) {
//              joinEdges.addAll(joinEdgesForGiven);
//            }
//          }
//        }
//
//        // check if the relation is the last piece of outer join
//        boolean endInnerRelation = false;
//        for (JoinEdge joinEdge: joinEdges) {
//          switch(joinEdge.getJoinType()) {
//            case LEFT_ANTI:
//            case RIGHT_ANTI:
//            case LEFT_SEMI:
//            case RIGHT_SEMI:
//            case LEFT_OUTER:
//            case RIGHT_OUTER:
//            case FULL_OUTER:
//              endInnerRelation = true;
//              if (checkingRelations.size() <= 1) {
//                checkingRelations.add(relation);
//              }
//              break;
//          }
//        }
//
//        if (endInnerRelation) {
//          break;
//        }
//
//        checkingRelations.add(relation);
//      }
//
//      remainRelations.removeAll(checkingRelations);
//
//      // Find the best join pair among all joinable operators in candidate set.
//      while (checkingRelations.size() > 1) {
//        LinkedHashSet<String[]> removingJoinEdges = new LinkedHashSet<String[]>();
//        bestPair = getBestPair(plan, joinGraph, checkingRelations, removingJoinEdges);
//
//        checkingRelations.remove(bestPair.getLeftRelation());
//        checkingRelations.remove(bestPair.getRightRelation());
//        for (String[] joinEdge: removingJoinEdges) {
//          // remove the edge of the best pair from join graph
//          joinGraph.removeEdge(joinEdge[0], joinEdge[1]);
//        }
//
//        latestJoin = createJoinNode(plan, bestPair);
//        checkingRelations.add(latestJoin);
//
//        // all logical nodes should be registered to corresponding blocks
//        block.registerNode(latestJoin);
//      }
//
//      // new Logical block should be the first entry of new Set
//      checkingRelations.addAll(remainRelations);
//      remainRelations = checkingRelations;
//    }
//
//    JoinNode joinTree = (JoinNode) remainRelations.iterator().next();
//    // all generated nodes should be registered to corresponding blocks
//    block.registerNode(joinTree);
//    return new FoundJoinOrder(joinTree, getCost(joinTree));
  }

  private void prepareGraphUpdate(LogicalPlan plan, LogicalPlan.QueryBlock block, List<JoinEdge> edges,
                                  JoinedRelationsVertex vertex, boolean isLeftVertex,
                                  Set<JoinEdge> willBeAdded, Set<JoinEdge> willBeRemoved) {
    if (edges != null) {
      for (JoinEdge edge : edges) {
        if (!isEqualsOrCommutative(vertex.getJoinEdge(), edge)) {
          JoinNode newNode;
          if (isLeftVertex) {
            newNode = JoinOrderingUtil.createJoinNode(plan, edge.getJoinType(), vertex, edge.getRightVertex(),
                edge.getSingletonJoinQual());
            willBeAdded.add(new JoinEdge(newNode, vertex, edge.getRightVertex()));
          } else {
            newNode = JoinOrderingUtil.createJoinNode(plan, edge.getJoinType(), edge.getLeftVertex(), vertex,
                edge.getSingletonJoinQual());
            willBeAdded.add(new JoinEdge(newNode, edge.getLeftVertex(), vertex));
          }
          block.registerNode(newNode);
        }
        willBeRemoved.add(edge);
      }
    }
  }

  /**
   * Find the best join pair among all joinable operators in candidate set.
   *
   * @param context
   * @param joinGraph a join graph which consists of vertices and edges, where vertex is relation and
   *                  each edge is join condition.
   * @param vertexes candidate operators to be joined.
   * @return The best join pair among them
   * @throws PlanningException
   */
  private JoinEdge getBestPair(JoinEdgeFinderContext context, JoinGraphContext graphContext, Set<JoinVertex> vertexes)
      throws PlanningException {
    double minCost = Double.MAX_VALUE;
    JoinEdge bestJoin = null;

    double minNonCrossJoinCost = Double.MAX_VALUE;
    JoinEdge bestNonCrossJoin = null;

    for (JoinVertex outer : vertexes) {
      for (JoinVertex inner : vertexes) {
        if (outer.equals(inner)) {
          continue;
        }

        context.reset();
        JoinEdge foundJoin = findJoin(context, graphContext.getJoinGraph(), graphContext.getMostLeftVertex(),
            outer, inner);
        if (foundJoin == null) {
          continue;
        }
        Set<EvalNode> additionalPredicates = JoinOrderingUtil.findJoinConditionForJoinVertex(
            graphContext.getJoinPredicateCandidates(), foundJoin);
        graphContext.removePredicateCandidates(additionalPredicates);
        foundJoin = JoinOrderingUtil.addPredicates(foundJoin, additionalPredicates);
        double cost = getCost(foundJoin);

        if (cost < minCost) {
          minCost = cost;
          bestJoin = foundJoin;
        }

        // Keep the min cost join
        // But, if there exists a qualified join, the qualified join must be chosen
        // rather than cross join regardless of cost.
        if (foundJoin.hasJoinQual()) {
          if (cost < minNonCrossJoinCost) {
            minNonCrossJoinCost = cost;
            bestNonCrossJoin = foundJoin;
          }
        }
      }
    }

    if (bestNonCrossJoin != null) {
      return bestNonCrossJoin;
    } else {
      return bestJoin;
    }
  }

  private static class JoinEdgeFinderContext {
    private Set<JoinVertex> visited = TUtil.newHashSet();

    public void reset() {
      visited.clear();
    }
  }

  /**
   * Find a join between two logical operator trees
   *
   * @return If there is no join condition between two relation, it returns NULL value.
   */
  private static JoinEdge findJoin(final JoinEdgeFinderContext context, final JoinGraph joinGraph, JoinVertex begin,
                                   final JoinVertex leftTarget, final JoinVertex rightTarget)
      throws PlanningException {

    context.visited.add(begin);

    // Find the matching edge from begin
    if (begin.equals(leftTarget)) {
//    if (isEqualsOrExchangeable(joinGraph, begin, leftTarget)) {
      List<JoinEdge> edgesFromLeftTarget = joinGraph.getOutgoingEdges(leftTarget);
      if (edgesFromLeftTarget != null) {
        for (JoinEdge edgeFromLeftTarget : edgesFromLeftTarget) {
//          if (isEqualsOrExchangeable(joinGraph, edgeFromLeftTarget.getRightVertex(), rightTarget)) {
          if (edgeFromLeftTarget.getRightVertex().equals(rightTarget)) {
            return edgeFromLeftTarget;
          }
        }
      }
      // not found
      return null;
    } else {
      // move to right if associative
      for (JoinVertex reacheableVertex : getAllReacheableVertexesByCommutativity(joinGraph, begin)) {
        if (begin.equals(reacheableVertex)) continue;
        List<JoinEdge> edges = joinGraph.getOutgoingEdges(reacheableVertex);
        if (edges != null) {
          for (JoinEdge edge : edges) {
            for (JoinEdge associativeEdge : getAllAssociativeEdges(joinGraph, edge)) {
              JoinVertex willBeVisited = associativeEdge.getLeftVertex();
              if (!context.visited.contains(willBeVisited)) {
                JoinEdge found = findJoin(context, joinGraph, associativeEdge.getLeftVertex(), leftTarget, rightTarget);
                if (found != null) {
                  return found;
                }
              }
            }
          }
        }
      }
      // not found
      return null;
    }
  }

  private static Set<JoinEdge> getAllAssociativeEdges(JoinGraph graph, JoinEdge edge) {
    Set<JoinEdge> associativeEdges = TUtil.newHashSet();
    // find all associative edge pairs which begin at the start vertex
    JoinVertex start = edge.getRightVertex();
    List<JoinEdge> candidateEdges = graph.getOutgoingEdges(start);
    if (candidateEdges != null) {
      for (JoinEdge candidateEdge : candidateEdges) {
        if (!isEqualsOrCommutative(edge, candidateEdge) &&
            JoinOrderingUtil.isAssociativeJoin(edge.getJoinType(), candidateEdge.getJoinType())) {
          associativeEdges.add(candidateEdge);
        }
      }
    }
    return associativeEdges;
  }

  /**
   *
   * @param graph
   * @param v1
   * @param v2
   * @return
   */
  private static boolean isEqualsOrExchangeable(JoinGraph graph, JoinVertex v1, JoinVertex v2) {
    for (JoinVertex commutative : getAllReacheableVertexesByCommutativity(graph, v1)) {
      if (v2.equals(commutative)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isEqualsOrCommutative(JoinEdge edge1, JoinEdge edge2) {
    if (edge1.equals(edge2) || isCommutative(edge1, edge2)) {
      return true;
    }
    return false;
  }

  private static boolean isCommutative(JoinEdge edge1, JoinEdge edge2) {
    if (edge1.getLeftVertex().equals(edge2.getRightVertex()) &&
        edge1.getRightVertex().equals(edge2.getLeftVertex()) &&
        edge1.getJoinSpec().equals(edge2.getJoinSpec()) &&
        PlannerUtil.isCommutativeJoin(edge1.getJoinType())) {
      return true;
    }
    return false;
  }

  private static Set<JoinVertex> getAllReacheableVertexesByCommutativity(JoinGraph graph, JoinVertex from) {
    Set<JoinVertex> founds = TUtil.newHashSet();
    getAllReacheableVertexesByCommutativity(founds, graph, from);
    return founds;
  }

  private static void getAllReacheableVertexesByCommutativity(Set<JoinVertex> founds, JoinGraph graph,
                                                              JoinVertex vertex) {
    founds.add(vertex);
    Set<JoinVertex> foundAtThis = TUtil.newHashSet();
    List<JoinEdge> candidateEdges = graph.getOutgoingEdges(vertex);
    if (candidateEdges != null) {
      for (JoinEdge candidateEdge : candidateEdges) {
        if (PlannerUtil.isCommutativeJoin(candidateEdge.getJoinType())
            && !founds.contains(candidateEdge.getRightVertex())) {
          foundAtThis.add(candidateEdge.getRightVertex());
        }
      }
      if (foundAtThis.size() > 0) {
        founds.addAll(foundAtThis);
        for (JoinVertex v : foundAtThis) {
          getAllReacheableVertexesByCommutativity(founds, graph, v);
        }
      }
    }
  }

  /**
   * Getting a cost of one join
   * @param joinEdge
   * @return
   */
  public static double getCost(JoinEdge joinEdge) {
    double filterFactor = 1;
    if (joinEdge.hasJoinQual()) {
      // TODO - should consider join type
      // TODO - should statistic information obtained from query history
      filterFactor = filterFactor * Math.pow(DEFAULT_SELECTION_FACTOR, joinEdge.getJoinQual().size());
      return getCost(joinEdge.getLeftVertex().getCorrespondingNode()) *
          getCost(joinEdge.getRightVertex().getCorrespondingNode()) * filterFactor;
    } else {
      // make cost bigger if cross join
      return Math.pow(getCost(joinEdge.getLeftVertex().getCorrespondingNode()) *
          getCost(joinEdge.getRightVertex().getCorrespondingNode()), 2);
    }
  }

  // TODO - costs of other operator operators (e.g., group-by and sort) should be computed in proper manners.
  public static double getCost(LogicalNode node) {
    switch (node.getType()) {

    case PROJECTION:
      ProjectionNode projectionNode = (ProjectionNode) node;
      return getCost(projectionNode.getChild());

    case JOIN:
      JoinNode joinNode = (JoinNode) node;
      double filterFactor = 1;
      if (joinNode.hasJoinQual()) {
        filterFactor = Math.pow(DEFAULT_SELECTION_FACTOR,
            AlgebraicUtil.toConjunctiveNormalFormArray(joinNode.getJoinQual()).length);
        return getCost(joinNode.getLeftChild()) * getCost(joinNode.getRightChild()) * filterFactor;
      } else {
        return Math.pow(getCost(joinNode.getLeftChild()) * getCost(joinNode.getRightChild()), 2);
      }

    case SELECTION:
      SelectionNode selectionNode = (SelectionNode) node;
      return getCost(selectionNode.getChild()) *
          Math.pow(DEFAULT_SELECTION_FACTOR, AlgebraicUtil.toConjunctiveNormalFormArray(selectionNode.getQual()).length);

    case TABLE_SUBQUERY:
      TableSubQueryNode subQueryNode = (TableSubQueryNode) node;
      return getCost(subQueryNode.getSubQuery());

    case SCAN:
      ScanNode scanNode = (ScanNode) node;
      if (scanNode.getTableDesc().getStats() != null) {
        double cost = ((ScanNode)node).getTableDesc().getStats().getNumBytes();
        return cost;
      } else {
        return Long.MAX_VALUE;
      }

    case UNION:
      UnionNode unionNode = (UnionNode) node;
      return getCost(unionNode.getLeftChild()) + getCost(unionNode.getRightChild());

    case EXCEPT:
    case INTERSECT:
      throw new UnsupportedOperationException("getCost() does not support EXCEPT or INTERSECT yet");

    default:
      // all binary operators (join, union, except, and intersect) are handled in the above cases.
      // So, we need to handle only unary nodes in default.
      return getCost(((UnaryNode) node).getChild());
    }
  }
}