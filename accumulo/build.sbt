

name := "geotrellis-accumulo"
libraryDependencies ++= Seq(
  "org.apache.accumulo" % "accumulo-core" % Version.accumulo
    exclude("org.jboss.netty", "netty")
    exclude("org.apache.hadoop", "hadoop-client"),
  sparkCore % "provided",
  spire,
  scalatest % "test")

fork in Test := false
parallelExecution in Test := false

initialCommands in console :=
  """
  import geotrellis.raster._
  import geotrellis.vector._
  import geotrellis.proj4._
  import geotrellis.spark._
  import geotrellis.spark.util._
  import geotrellis.spark.tiling._
  import geotrellis.spark.io.accumulo._
  """

lazy val buildSettings = assemblySettings ++ Seq(
  test in assembly := {},
  assemblyOption in assembly ~= { _.copy(includeScala = true) },
  assemblyMergeStrategy in assembly := {
    case "reference.conf"        ⇒ MergeStrategy.concat
    case "application.conf"      ⇒ MergeStrategy.concat
    case "META-INF/MANIFEST.MF"  ⇒ MergeStrategy.discard
    case "META-INF\\MANIFEST.MF" ⇒ MergeStrategy.discard
    case "META-INF/ECLIPSEF.RSA" ⇒ MergeStrategy.discard
    case "META-INF/ECLIPSEF.SF"  ⇒ MergeStrategy.discard
    case _                       ⇒ MergeStrategy.first
  }
)