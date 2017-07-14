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

import geotrellis.spark.LayerId
import geotrellis.spark.io._
import geotrellis.spark.io.avro.{AvroEncoder, AvroRecordCodec}
import geotrellis.spark.io.avro.codecs.KeyValueRecordCodec
import geotrellis.spark.io.index.KeyIndex

import org.apache.accumulo.core.data.{Range => ARange}
import org.apache.accumulo.core.security.Authorizations
import org.apache.avro.Schema
import org.apache.hadoop.io.Text
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import org.apache.accumulo.core.trace.DistributedTrace
import org.apache.accumulo.core.trace.Trace

class AccumuloValueReader(
  instance: AccumuloInstance,
  val attributeStore: AttributeStore
) extends ValueReader[LayerId] {

  val rowId = (index: Long) => new Text(AccumuloKeyEncoder.long2Bytes(index))

  def reader[K: AvroRecordCodec: JsonFormat: ClassTag, V: AvroRecordCodec](layerId: LayerId): Reader[K, V] = new Reader[K, V] {
    val header = attributeStore.readHeader[AccumuloLayerHeader](layerId)
    val keyIndex = attributeStore.readKeyIndex[K](layerId)
    val writerSchema = attributeStore.readSchema(layerId)
    val codec = KeyValueRecordCodec[K, V]

    def read(key: K): V = {

      DistributedTrace.enable()
      val scanTrace = Trace.on("checkAccumuloScannerRead")

      val startTime = System.currentTimeMillis()
      val scanner = instance.connector.createScanner(header.tileTable, new Authorizations())
      scanner.setRange(new ARange(rowId(keyIndex.toIndex(key))))
      scanner.fetchColumnFamily(columnFamily(layerId))

      val tiles = scanner.iterator
        .map { entry =>
          AvroEncoder.fromBinary(writerSchema, entry.getValue.get)(codec)
        }
        .flatMap { pairs: Vector[(K, V)] =>
          pairs.filter(pair => pair._1 == key)
        }
        .toVector

      if (tiles.isEmpty) {
        throw new ValueNotFoundError(key, layerId)
      } else if (tiles.size > 1) {
        throw new LayerIOError(s"Multiple values found for $key for layer $layerId")
      } else {
        val traceId:Long = Trace.currentTraceId()
        println(s"------trace id: $traceId")
        Trace.off()
        val executionTime = System.currentTimeMillis() - startTime
        println(s"execution time $executionTime")

        tiles.head._2
      }
    }
  }
}

object AccumuloValueReader {
  def apply[K: AvroRecordCodec: JsonFormat: ClassTag, V: AvroRecordCodec](
    instance: AccumuloInstance,
    attributeStore: AttributeStore,
    layerId: LayerId
  ): Reader[K, V] =
    new AccumuloValueReader(instance, attributeStore).reader[K, V](layerId)

  def apply(instance: AccumuloInstance): AccumuloValueReader =
    new AccumuloValueReader(
      instance = instance,
      attributeStore = AccumuloAttributeStore(instance.connector))
}
