package geotrellis.spark.io.geowave

import geotrellis.spark._
import geotrellis.vector._

import org.apache.accumulo.core.client.mock.MockInstance
import org.scalatest._
import com.vividsolutions.jts.{geom => jts}
import mil.nga.giat.geowave.core.geotime.ingest._
import mil.nga.giat.geowave.core.store._
import mil.nga.giat.geowave.core.store.query._
import mil.nga.giat.geowave.core.geotime.store.query.SpatialQuery
import mil.nga.giat.geowave.core.store.index.PrimaryIndex
import mil.nga.giat.geowave.core.geotime.GeometryUtils
import mil.nga.giat.geowave.datastore.accumulo._
import mil.nga.giat.geowave.datastore.accumulo.index.secondary.AccumuloSecondaryIndexDataStore
import mil.nga.giat.geowave.datastore.accumulo.metadata._
import mil.nga.giat.geowave.adapter.vector._
import mil.nga.giat.geowave.mapreduce.input._
import mil.nga.giat.geowave.core.store.operations.remote.options.DataStorePluginOptions
import mil.nga.giat.geowave.datastore.accumulo.operations.config.AccumuloRequiredOptions
import mil.nga.giat.geowave.datastore.accumulo.operations.config.AccumuloOptions
import org.geotools.feature._
import org.geotools.feature.simple._
import org.opengis.feature.simple._
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.rdd._

import scala.util.Properties

class GeoWaveFeatureRDDReaderSpec extends FunSpec { self: Suite =>
  implicit def id(x: Map[String, Any]) : Seq[(String, Any)] = x.toSeq
  //val accumuloInstance = MockAccumuloInstance()
  val fakeInstance = new MockInstance("fake")
  println("DIAGNOSTICS----------------------")
  println(fakeInstance.getZooKeepers())
  println(fakeInstance.getInstanceName())
  val philly = Point(-75.5859375, 40.713955826286046)

  val builder = new SimpleFeatureTypeBuilder()
  val ab = new AttributeTypeBuilder()
  builder.setName("TestType")
  builder.add(ab.binding(classOf[jts.Point]).nillable(false).buildDescriptor("geometry"))

  val features = Array(Feature(philly, Map[String, Any]()))

  val zookeeper = "localhost"
  val instanceName = "fake"
  val username = "root"
  val password = ""
  val featureType = builder.buildFeatureType()
  val featureRDD = sc.parallelize(features)

  def setKryoRegistrator(conf: SparkConf): Unit =
    conf.set("spark.kryo.registrator", "geotrellis.spark.io.kryo.KryoRegistrator")

  lazy val _sc: SparkContext = {
    System.setProperty("spark.driver.port", "0")
    System.setProperty("spark.hostPort", "0")
    System.setProperty("spark.ui.enabled", "false")

    val conf = new SparkConf()
    conf
      .setMaster("local")
      .setAppName("Test Context")

    // Shortcut out of using Kryo serialization if we want to test against
    // java serialization.
    if(Properties.envOrNone("GEOTRELLIS_USE_JAVA_SER") == None) {
      conf
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.kryoserializer.buffer.max", "500m")
        .set("spark.kryo.registrationRequired","false")
      setKryoRegistrator(conf)
    }
    conf
      .set("*.sink.servlet.class","")

    val sparkContext = new SparkContext(conf)

    System.clearProperty("spark.driver.port")
    System.clearProperty("spark.hostPort")
    System.clearProperty("spark.ui.enabled")

    sparkContext
  }

  implicit def sc: SparkContext = _sc

  describe("???") {
    it("Should...") {
      val count1 = GeoWaveFeatureRDDReader.read(
        zookeeper,
        instanceName,
        username,
        password,
        "testpoint",
        featureType
      ).count()

      GeoWaveFeatureRDDWriter.write(
        featureRDD,
        zookeeper,
        instanceName,
        username,
        password,
        "testpoint",
        featureType
      )

      val count2 = GeoWaveFeatureRDDReader.read(
        zookeeper,
        instanceName,
        username,
        password,
        "testpoint",
        featureType
      ).count()

      assert(count1 == 0)
      assert(count2 == 1)
    }
  }
}
