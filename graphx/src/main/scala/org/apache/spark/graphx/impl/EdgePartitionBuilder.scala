/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.graphx.impl

import scala.reflect.ClassTag
import scala.util.Sorting

import org.apache.spark.graphx._
import org.apache.spark.graphx.util.collection.PrimitiveKeyOpenHashMap
import org.apache.spark.util.collection.PrimitiveVector

private[graphx]
class FreshEdgePartitionBuilder[@specialized(Long, Int, Double) ED: ClassTag](size: Int = 64) {
  var edges = new PrimitiveVector[Edge[ED]](size)
  private var vertexIds: VertexIdToIndexMap = null
  init()
  private def init() {
    vertexIds = new VertexIdToIndexMap
  }

  /** Add a new edge to the partition. */
  def add(src: VertexId, dst: VertexId, d: ED) {
    edges += Edge(src, dst, d)
    vertexIds.add(src)
    vertexIds.add(dst)
  }

  def toEdgePartition: EdgePartition[ED] = {
    val edgeArray = edges.trim().array
    Sorting.quickSort(edgeArray)(Edge.lexicographicOrdering)
    val srcIds = new Array[VertexId](edgeArray.size)
    val dstIds = new Array[VertexId](edgeArray.size)
    val data = new Array[ED](edgeArray.size)
    val index = new PrimitiveKeyOpenHashMap[VertexId, Int]
    // Copy edges into columnar structures, tracking the beginnings of source vertex id clusters and
    // adding them to the index
    if (edgeArray.length > 0) {
      index.update(srcIds(0), 0)
      var currSrcId: VertexId = srcIds(0)
      var i = 0
      while (i < edgeArray.size) {
        srcIds(i) = edgeArray(i).srcId
        dstIds(i) = edgeArray(i).dstId
        data(i) = edgeArray(i).attr
        if (edgeArray(i).srcId != currSrcId) {
          currSrcId = edgeArray(i).srcId
          index.update(currSrcId, i)
        }
        i += 1
      }
    }

    val localSrcIds = new Array[Int](edgeArray.size)
    val localDstIds = new Array[Int](edgeArray.size)
    // Populate the local id columns using the VertexPartition
    var i = 0
    while (i < edgeArray.size) {
      localSrcIds(i) = vertexIds.getPos(edgeArray(i).srcId)
      localDstIds(i) = vertexIds.getPos(edgeArray(i).dstId)
      i += 1
    }

    new EdgePartition(srcIds, dstIds, localSrcIds, localDstIds, data, index)
  }
}

private[graphx]
class VertexPreservingEdgePartitionBuilder[@specialized(Long, Int, Double) ED: ClassTag](size: Int = 64) {
  var edges = new PrimitiveVector[Edge[ED]](size)

  /** Add a new edge to the partition. */
  def add(src: VertexId, dst: VertexId, srcLocal: Int, dstLocal: Int, d: ED) {
    val e = Edge(src, dst, d)
    e.localSrcId = srcLocal
    e.localDstId = dstLocal
    edges += e
  }

  def toEdgePartition: EdgePartition[ED] = {
    val edgeArray = edges.trim().array
    Sorting.quickSort(edgeArray)(Edge.lexicographicOrdering)
    val srcIds = new Array[VertexId](edgeArray.size)
    val dstIds = new Array[VertexId](edgeArray.size)
    val localSrcIds = new Array[Int](edgeArray.size)
    val localDstIds = new Array[Int](edgeArray.size)
    val data = new Array[ED](edgeArray.size)
    val index = new PrimitiveKeyOpenHashMap[VertexId, Int]
    // Copy edges into columnar structures, tracking the beginnings of source vertex id clusters and
    // adding them to the index
    if (edgeArray.length > 0) {
      index.update(srcIds(0), 0)
      var currSrcId: VertexId = srcIds(0)
      var i = 0
      while (i < edgeArray.size) {
        srcIds(i) = edgeArray(i).srcId
        dstIds(i) = edgeArray(i).dstId
        localSrcIds(i) = edgeArray(i).localSrcId
        localDstIds(i) = edgeArray(i).localDstId
        data(i) = edgeArray(i).attr
        if (edgeArray(i).srcId != currSrcId) {
          currSrcId = edgeArray(i).srcId
          index.update(currSrcId, i)
        }
        i += 1
      }
    }

    new EdgePartition(srcIds, dstIds, localSrcIds, localDstIds, data, index)
  }
}
