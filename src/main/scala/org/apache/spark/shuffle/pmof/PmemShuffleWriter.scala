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

package org.apache.spark.shuffle.pmof

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.network.pmof.RdmaTransferService
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.shuffle.{BaseShuffleHandle, IndexShuffleBlockResolver, ShuffleWriter}
import org.apache.spark.serializer.{SerializationStream, SerializerInstance, SerializerManager}
import org.apache.spark.storage._
import org.apache.spark.executor.ShuffleWriteMetrics

import org.apache.spark.storage.pmof.PersistentMemoryHandler

import java.util.UUID
import java.io.{ByteArrayOutputStream, OutputStream, BufferedOutputStream}

private[spark] class PersistentMemoryWriterPartition(
    serializerManager: SerializerManager,
    serializerInstance: SerializerInstance,
    blockId: BlockId,
    writeMetrics: ShuffleWriteMetrics 
) extends Logging {
  var bs: OutputStream = _
  var objStream: SerializationStream = _
  //var ts: BufferedOutputStream = _
  var stream: ByteArrayOutputStream = _
  var size: Long = 0
  var initialized = false
  //var objStream: ObjectOutputStream = _

  def set(key: Any, value: Any): Long = {
    if (!initialized) {
      stream = new ByteArrayOutputStream()
      //ts = new BufferedOutputStream(new TimeTrackingOutputStream(writeMetrics, stream))
      bs = serializerManager.wrapStream(blockId, stream)
      objStream = serializerInstance.serializeStream(bs)
      initialized = true
    }
    objStream.writeObject(key)
    objStream.writeObject(value)
    /* check size here,
     * if it exceeds maximun block size,
     * we will write to PM and continue
    */
    stream.size
  }

  def get() : Array[Byte] = {
    if (initialized) {
      objStream.flush()
      bs.flush()
      stream.flush()
      val data = stream.toByteArray
      size += data.size
      stream.reset()
      data
    } else {
      Array[Byte]()
    }
  }
  
  def close() {
    if (initialized) {
      //logInfo("PersistentMemoryHandlerPartition: stream closed.")
      objStream.close()
      bs.close()
      stream.close()
    }
  }
}

private[spark] class PmemShuffleWriter[K, V, C](
                                                 shuffleBlockResolver: IndexShuffleBlockResolver,
                                                 handle: BaseShuffleHandle[K, V, C],
                                                 mapId: Int,
                                                 context: TaskContext,
                                                 conf: SparkConf
                                                 )
  extends ShuffleWriter[K, V] with Logging {
  private val dep = handle.dependency
  private val blockManager = SparkEnv.get.blockManager
  private var mapStatus: MapStatus = _
  private val writeMetrics = context.taskMetrics().shuffleWriteMetrics
  private val stageId = dep.shuffleId
  private val partitioner = dep.partitioner
  private val numPartitions = partitioner.numPartitions
  private val serInstance: SerializerInstance = dep.serializer.newInstance()

  val enable_rdma: Boolean = conf.getBoolean("spark.shuffle.pmof.enable_rdma", defaultValue = true)
  val enable_pmem: Boolean = conf.getBoolean("spark.shuffle.pmof.enable_pmem", defaultValue = true)
  val path_list = conf.get("spark.shuffle.pmof.pmem_list").split(",").map(_.trim).distinct

  val maxPoolSize: Long = conf.getLong("spark.shuffle.pmof.pmpool_size", defaultValue = 1073741824)
  val maxStages: Int = conf.getInt("spark.shuffle.pmof.max_stage_num", defaultValue = 1000)
  val maxMaps: Int = conf.getInt("spark.shuffle.pmof.max_task_num", defaultValue = 1000)

  val blockId = TempShuffleBlockId(UUID.randomUUID())
  var devId = SparkEnv.get.executorId.toInt - 1
  //logInfo("devId is " + devId + ", path_list: \n\t" + path_list.mkString("\n\t"))
  var path: String = path_list(devId)

  val persistentMemoryWriter: PersistentMemoryHandler = PersistentMemoryHandler.getPersistentMemoryHandler(path, maxPoolSize, maxStages, maxMaps)
  persistentMemoryWriter.initializeShuffle(stageId, mapId, numPartitions)
  val partitionLengths: Array[Long] = Array.fill[Long](numPartitions)(0)

  /**
   * Are we in the process of stopping? Because map tasks can call stop() with success = true
   * and then call stop() with success = false if they get an exception, we want to make sure
   * we don't try deleting files, etc twice.
   */
  private var stopping = false

  def maySpillToPM(size: Long, partitionId: Int, partitionBuffer: PersistentMemoryWriterPartition) {
    if (size >= 33554432) {
      val tmp_data = partitionBuffer.get()
      persistentMemoryWriter.tryAddPartition(stageId, mapId, partitionId, tmp_data.size)
      persistentMemoryWriter.write(stageId, mapId, partitionId, tmp_data)
    }
  }
 
  /** 
  * Call PMDK to write data to persistent memory
  * Original Spark writer will do write and mergesort in this function,
  * while by using pmdk, we can do that once since pmdk supports transaction.
  */
  override def write(records: Iterator[Product2[K, V]]): Unit = {
    // TODO: keep checking if data need to spill to disk when PM capacity is not enough.
    // TODO: currently, we apply processed records to PM.

    val partitionBufferArray = Array.fill[PersistentMemoryWriterPartition](numPartitions)(new PersistentMemoryWriterPartition(blockManager.serializerManager, serInstance, blockId, writeMetrics))
    if (dep.mapSideCombine) { // do aggragation
      if (dep.aggregator.isDefined) {
        val iter = dep.aggregator.get.combineValuesByKey(records, context)
        while (iter.hasNext) {     
          // since we need to write same partition (key, value) togethor, do a partition index here
          val elem = iter.next()
          val partitionId: Int = partitioner.getPartition(elem._1)
          val tmp_size = partitionBufferArray(partitionId).set(elem._1, elem._2)
          maySpillToPM(tmp_size, partitionId, partitionBufferArray(partitionId))
        }
      } else if (dep.aggregator.isEmpty) {
        throw new IllegalStateException("Aggregator is empty for map-side combine")
      }
    } else { // no aggregation
      while (records.hasNext) {     
        // since we need to write same partition (key, value) togethor, do a partition index here
        val elem = records.next()
        val partitionId: Int = partitioner.getPartition(elem._1)
        val tmp_size = partitionBufferArray(partitionId).set(elem._1, elem._2)
        maySpillToPM(tmp_size, partitionId, partitionBufferArray(partitionId))
      }
    }
    for (i <- 0 until numPartitions) {
      val data = partitionBufferArray(i).get()
      persistentMemoryWriter.tryAddPartition(stageId, mapId, i, data.size)
      persistentMemoryWriter.write(stageId, mapId, i, data)
      writeMetrics.incBytesWritten(partitionBufferArray(i).size)
      partitionLengths(i) = partitionBufferArray(i).size
      partitionBufferArray(i).close()
    }

    var output_str : String = ""
    for (i <- 0 until numPartitions) {
      output_str += "\tPartition " + i + ": " + partitionLengths(i) + "\n"
    }
    logInfo("shuffle_" + dep.shuffleId + "_" + mapId + ": \n" + output_str);

    val shuffleServerId = blockManager.shuffleServerId
    if (enable_rdma) {
      val blockManagerId: BlockManagerId =
        BlockManagerId(shuffleServerId.executorId, shuffleServerId.host,
          RdmaTransferService.getTransferServiceInstance(blockManager).port, shuffleServerId.topologyInfo)
      mapStatus = MapStatus(blockManagerId, partitionLengths)
    } else {
      mapStatus = MapStatus(shuffleServerId, partitionLengths)
    }
  }

  /** Close this writer, passing along whether the map completed */
  override def stop(success: Boolean): Option[MapStatus] = {
    try {
      if (stopping) {
        return None
      }
      stopping = true
      if (success) {
        Option(mapStatus)
      } else {
        None
      }
    } finally {
      // Clean up our sorter, which may have its own intermediate files
      val startTime = System.nanoTime()
      writeMetrics.incWriteTime(System.nanoTime - startTime)
    }
  }
}
