/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.io.accumulo

import geotrellis.spark.util._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._

import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.accumulo.core.data.{Key, Mutation, Value}
import org.apache.accumulo.core.client.mapreduce.AccumuloFileOutputFormat
import org.apache.accumulo.core.client.BatchWriterConfig
import com.typesafe.config.ConfigFactory
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream._

import java.util.UUID
import java.util.concurrent.Executors

object AccumuloWriteStrategy {
  val threads = ConfigFactory.load().getThreads("geotrellis.accumulo.threads.rdd.write")

  def DEFAULT = HdfsWriteStrategy("/geotrellis-ingest")
}

sealed trait AccumuloWriteStrategy {
  def write(kvPairs: RDD[(Key, Value)], instance: AccumuloInstance, table: String): Unit
}

/**
 * This strategy will perfom Accumulo bulk ingest. Bulk ingest requires that sorted records be written to the
 * filesystem, preferbly HDFS, before Accumulo is able to ingest them. After the ingest is finished
 * the nodes will likely go through a period of high load as they perform major compactions.
 *
 * Note: Giving relative URLs will cause HDFS to use the `fs.defaultFS` property in `core-site.xml`.
 * If not specified this will default to local ('file:/') system, this is undesriable.
 *
 * @param ingestPath Path where spark will write RDD records for ingest
 */
case class HdfsWriteStrategy(ingestPath: Path) extends AccumuloWriteStrategy {
  /** Requires that the RDD be pre-sorted */
  def write(kvPairs: RDD[(Key, Value)], instance: AccumuloInstance, table: String): Unit = {
    val sc = kvPairs.sparkContext
    val job = Job.getInstance(sc.hadoopConfiguration)
    instance.setAccumuloConfig(job)
    val conf = job.getConfiguration
    val outPath = HdfsUtils.tmpPath(ingestPath, UUID.randomUUID.toString, conf)
    val failuresPath = outPath.suffix("-failures")

    HdfsUtils.ensurePathExists(failuresPath, conf)
    kvPairs
      .sortByKey()
      .saveAsNewAPIHadoopFile(
        outPath.toString,
        classOf[Key],
        classOf[Value],
        classOf[AccumuloFileOutputFormat],
        conf)

    val ops = instance.connector.tableOperations()
    ops.importDirectory(table, outPath.toString, failuresPath.toString, true)

    // cleanup ingest directories on success
    val fs = ingestPath.getFileSystem(conf)
    if( fs.exists(new Path(outPath, "_SUCCESS")) ) {
      fs.delete(outPath, true)
      fs.delete(failuresPath, true)
    } else {
      throw new java.io.IOException(s"Accumulo bulk ingest failed at $ingestPath")
    }
  }
}
object HdfsWriteStrategy {
  def apply(ingestPath: String): HdfsWriteStrategy = HdfsWriteStrategy(new Path(ingestPath))
}

/**
 * This strategy will create one BatchWriter per partition and attempt to stream the records to the target tablets.
 * In order to gain some parallism this strategy will create a number of splits in the target table equal to the number
 * of tservers in the cluster. This is suitable for smaller ingests, or where HdfsWriteStrategy is otherwise not possible.
 *
 * This strategy will not create splits before starting to write. If you wish to do that use [[AccumuloUtils.getSplits]] first.
 *
 * There is a problem in Accumulo 1.6 (fixed in 1.7) where the split creation does not wait for the resulting
 * empty tablets to distribute through the cluster before returning. This will create a warm-up period where the
 * pressure the ingest writers on that node will delay tablet re-balancing.
 *
 * The speed of the ingest can be improved by setting `tserver.wal.sync.method=hflush` in accumulo shell.
 * Note: this introduces higher chance of data loss due to sudden node failure.
 *
 * BatchWriter is notified of the tablet migrations and will follow them around the cluster.
 *
 * @param config Configuration for the BatchWriters
 */
case class SocketWriteStrategy(
  config: BatchWriterConfig = new BatchWriterConfig().setMaxMemory(128*1024*1024).setMaxWriteThreads(AccumuloWriteStrategy.threads),
  threads: Int = AccumuloWriteStrategy.threads
) extends AccumuloWriteStrategy {
  def write(kvPairs: RDD[(Key, Value)], instance: AccumuloInstance, table: String): Unit = {
    val serializeWrapper = KryoWrapper(config) // BatchWriterConfig is not java serializable
    val kwThreads = KryoWrapper(threads)
    kvPairs.foreachPartition { partition =>
      if(partition.nonEmpty) {
        val poolSize = kwThreads.value
        val pool = Executors.newFixedThreadPool(poolSize)
        val config = serializeWrapper.value
        val writer = instance.connector.createBatchWriter(table, config)

        val mutations: Process[Task, Mutation] =
          Process.unfold(partition){ iter =>
            if (iter.hasNext) {
              val (key, value) = iter.next()
              val mutation = new Mutation(key.getRow)
              mutation.put(key.getColumnFamily, key.getColumnQualifier, System.currentTimeMillis(), value)
              Some(mutation, iter)
            } else {
              None
            }
          }

        val writeChannel = channel.lift { (mutation: Mutation) => Task { writer.addMutation(mutation) } (pool) }
        val writes = mutations.tee(writeChannel)(tee.zipApply).map(Process.eval)
        nondeterminism.njoin(maxOpen = poolSize, maxQueued = poolSize)(writes)(Strategy.Executor(pool)).run.unsafePerformSync
        writer.close(); pool.shutdown()
      }
    }
  }
}

case class BatchWriteStrategy(
  config: BatchWriterConfig = new BatchWriterConfig().setMaxMemory(128*1024*1024).setMaxWriteThreads(1),
  threads:Int = 1
) extends AccumuloWriteStrategy {
  override def write(kvPairs: RDD[(Key, Value)], instance: AccumuloInstance, table: String): Unit = {
    //val serializeWrapper = KryoSerializer(config) // BatchWriterConfig is not java serializable
    val writer = instance.connector.createBatchWriter(table,config)
    kvPairs.foreach { case(key, value) ⇒
        val mutation = new Mutation(key.getRow)
        mutation.put(key.getColumnFamily, key.getColumnQualifier, System.currentTimeMillis(), value)
        writer.addMutation(mutation)
    }
    writer.close()
  }
}
