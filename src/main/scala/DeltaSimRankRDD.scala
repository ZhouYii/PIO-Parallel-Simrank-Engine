package io.prediction.examples.pfriendrecommendation

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkContext._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map


object DeltaSimRankRDD {

  var decay:Double = 0.8
  var numNodes:Int = 0

  /*
   Binary Search by src id
   Returns index of first satisfying edge in edge array
   */
  def binarySearchF(list: Array[Edge[Int]], target: VertexId)
                   (start: Int=0, end: Int=list.length-1): Int = {
    if (list(list.size - 1).srcId < target) return -1
    if (start>end) return -1
    val mid = start + (end-start+1)/2
    if (list(mid).srcId==target) {
      if (mid > 0 && list(mid - 1).srcId == target) {
        return binarySearchF(list, target)(start, mid-1)
      }
      return mid
    }
    else if (list(mid).srcId>target)
      return binarySearchF(list, target)(start, mid-1)
    else
      return binarySearchF(list, target)(mid+1, end)
  }

  /*
   Binary Search by destination id
   */
  def binarySearchE(list: Array[Edge[Int]], target: VertexId)
                   (start: Int=0, end: Int=list.length-1): Int = {
    if (start>end) return -1
    val mid = start + (end-start+1)/2
    if (list(mid).dstId==target) {
      if (mid > 0 && list(mid - 1).dstId == target) {
        return binarySearchE(list, target)(start, mid-1)
      }
      return mid
    }
    else if (list(mid).dstId>target)
      return binarySearchE(list, target)(start, mid-1)
    else
      return binarySearchE(list, target)(mid+1, end)
  }

  /*
  Accumulate all edges with same destination id

  @param e - the sorted edge array
  @param target - the target source vertex id
  @return a ListBuffer with all edges
   */
  def accumulateEdges(e:Array[Edge[Int]],
                      target:VertexId) : ListBuffer[Edge[Int]] = {
    val idx = binarySearchE(e, target)(0, e.size-1)
    var outEdges: ListBuffer[Edge[Int]] = ListBuffer()
    if(idx == -1){
      return outEdges
    }
    outEdges.append(e(idx))
    var tIdx = idx+1
    var edge:Edge[Int] = null
    // get upper edges
    while(tIdx < e.size){
      edge = e(tIdx)
      if(edge.dstId == target){
        outEdges.append(edge)
        tIdx += 1
      }else{
        tIdx = e.size
      }
    }
    // get lower edges
    tIdx = idx-1
    while(tIdx > -1){
      edge = e(tIdx)
      if(edge.dstId == target){
        outEdges.append(edge)
        tIdx -= 1
      }else{
        tIdx = -1
      }
    }
    outEdges
  }

  def sortByDst(a:Array[Edge[Int]]): Array[Edge[Int]] = {
        /*
         Quicksort
         */
        if (a.length < 2) a
        else {
            val pivot = a(a.length / 2).dstId
            // 'L'ess, 'E'qual, 'G'reater
            val partitions = a.groupBy( (e:Edge[Int]) => {
                if (e.dstId < pivot)
                  'L'
                else if (e.dstId > pivot)
                  'G'
                else
                  'E'
              })

            var sortedAccumulator: Array[Edge[Int]] = Array()
            List('L', 'E', 'G').foreach((c:Char) => {
                if (partitions.contains(c)) {
                  sortedAccumulator = sortedAccumulator ++ partitions(c)
                }
              })
            sortedAccumulator
        }
  }

  implicit class Crossable[X](xs: Traversable[X]) {
      def cross[Y](ys: Traversable[Y]) = for { x <- xs; y <- ys } yield (x, y)
    }

  def numAdjacent(target:VertexId, arr:Array[Edge[Int]]) : Int = {
    val lBound = binarySearchF(arr, target)(0, arr.size - 1)
    val rCandidate = binarySearchF(arr, target + 1)(0, arr.size -1 )
    var rBound = 0
    if (rCandidate == -1) {
      rBound = arr.size
    } else {
      rBound = rCandidate
    }
    return rBound - lBound
  }

  def calculateNthIter(numNodes:Int,
                     g:Graph[Int, Int],
                     prevDelta:RDD[((VertexId,VertexId),Double)],
                     dstSortedEdges:Array[Edge[Int]],
                     srcSortedEdges:Array[Edge[Int]]) : RDD[((VertexId,VertexId), Double)] = 
  {
    val newDelta = prevDelta.flatMap(pair => {
        val a = pair._1._1.toInt
        val b = pair._1._2.toInt
        val delta = pair._2

        val b_adj = accumulateEdges(dstSortedEdges, b)
                        .map(x=>x.srcId)
                        .toSeq
        val a_adj = accumulateEdges(dstSortedEdges, a)
                        .map(x=>x.srcId)
                        .toSeq
        val scorePairs = a_adj cross b_adj
        // MAP : output a ( (x,y), score) pair for relevant (x,y) pairs
        val score = scorePairs.filter(pair=> pair._1 != pair._2)
                  .map(pair => {
                      (pair, delta)
                    })
        score
        })
        // REDUCE : Collect scores by (k,v) pair
        .reduceByKey(_ + _)
        // Now calculate decay and scl
        .map(k => (k._1, k._2*decay/
                (numAdjacent(k._1._1, srcSortedEdges) + numAdjacent(k._1._2,srcSortedEdges))))

    newDelta
  }

  def calculateFirstIter(numNodes:Int,
                         g:Graph[Int, Int],
                         e:Array[Edge[Int]],
                         src_sorted:Array[Edge[Int]]) : RDD[((VertexId,VertexId), Double)] = 
  {
    val index_range = 0 to numNodes - 1
    val kv = g.vertices.flatMap(vertex => {
        // Find which nodes point to a diagonal vertex_id
        val adj = accumulateEdges(e, vertex._1)
                  .map(x => x.srcId)
                  .toSeq
        val candidates = adj cross adj
        candidates.filter(pair=> pair._1 != pair._2)
                  .map(pair => {
                      (pair, 1)
                  })
    })
    val newDelta = kv.reduceByKey(_ + _).map(k => {
      (k._1, k._2*decay/(numAdjacent(k._1._1, src_sorted) + numAdjacent(k._1._2, src_sorted)))
    })
    newDelta
  }

  def identityMatrix(sc:SparkContext, numCols:Long) : RDD[(Long, Double)] = {
    val numElements = numCols * numCols
    val arr = Array[Long]((0L to numElements).toList:_*)
    // (Score, Index), where (x,y) = (Index/numCols, Index%numCols)
    val pairs = arr.map(x => {
        if (x/numCols == x % numCols)
          (x, 1.0)
        else
          (x, 0.0)
      })
    sc.parallelize(pairs)
  }

  def matrixToIndices(x:Int, y:Int, numCols:Int) = {
    x + y * numCols
  }

  def joinDelta(prevIter:RDD[(Long, Double)], numCols:Int,
                delta:RDD[((VertexId,VertexId), Double)]) 
                : RDD[(Long,Double)] = 
  {
    val deltaToIndex:RDD[(Long,Double)] = delta.map(x => {
        val index = x._1._1*numCols + x._1._2
        (index, x._2)
      })

    val newIter = prevIter.leftOuterJoin(deltaToIndex)
    val newScores = newIter.map(x => 
      {
        val index = x._1
        if (x._2._2.isDefined) {
          (index, x._2._1 + x._2._2.get)
        } else {
          (index, x._2._1)
        }
      })
    newScores
  }

  def sortEdgeBySrc(a:Array[Edge[Int]]): Array[Edge[Int]] = {
        if (a.length < 2) a
        else {
            val pivot = a(a.length / 2).srcId
            // 'L'ess, 'E'qual, 'G'reater
            val partitions = a.groupBy( (e:Edge[Int]) => {
                if (e.srcId < pivot)
                  'L'
                else if (e.srcId > pivot)
                  'G'
                else
                  'E'
              })

            var sortedAccumulator: Array[Edge[Int]] = Array()
            List('L', 'E', 'G').foreach((c:Char) => {
                if (partitions.contains(c)) {
                  sortedAccumulator = sortedAccumulator ++ partitions(c)
                }
              })
            sortedAccumulator
        }
  }


  def compute(g:Graph[Int,Int], numIterations:Int, 
                  identityMatrix:RDD[(VertexId,Double)], newDecay:Double)
                                                    : RDD[(VertexId,Double)] = {
    numNodes = g.vertices.count().toInt
    decay = newDecay
    val sortBySrc = sortEdgeBySrc(g.edges.toArray)
    val edges = sortByDst(g.edges.toArray)

    // Build the identity matrix representing 0-th iteration of SimRank
    val s0 = identityMatrix
    val s0Delta = calculateFirstIter(numNodes, g, edges, sortBySrc)
    val s1 = joinDelta(s0, numNodes, s0Delta)

    var prevSimrank = s0
    var prevDelta = s0Delta

    for (i <- 1 to numIterations) {
      val nextIterDelta = calculateNthIter(numNodes, g, prevDelta, edges, sortBySrc)
      val nextIterSimrank = joinDelta(prevSimrank, numNodes, nextIterDelta)
      prevSimrank = nextIterSimrank
      prevDelta = nextIterDelta
    }

    prevSimrank
    /*
    // Sort so that the array of simrank scores correspond to the array indicies
    val array =  prevSimrank.toArray.sortWith((pair1, pair2) => {
                    val index1 = pair1._1
                    val index2 = pair2._2
                    index1 < index2
                  })
    val scores = array.map(x=>x._2)
    scores
    */
  }

  // Make all vertexId in one contiguous number range
  def normalizeGraph(g:Graph[Int,Int]) = {
    var counter = 0.toLong
    val hash = Map[VertexId, Long]()

    val v = g.vertices.map( pair => {
        hash(pair._1) = counter
        counter += 1
        (counter - 1, pair._2)
      })

    val e = g.edges.map( (e:Edge[Int]) => {
        if (hash.contains(e.srcId)) {
          e.srcId = hash(e.srcId)
        } else {
          hash += (e.srcId -> counter)
          counter += 1
          e.srcId = counter - 1
        }

        if (hash.contains(e.dstId)) {
          e.dstId = hash(e.dstId)
        } else {
          hash += (e.dstId -> counter)
          counter += 1
          e.dstId = counter - 1
        }
        e
      })

    val g2 = Graph(v,e)
    g2
  }

}


