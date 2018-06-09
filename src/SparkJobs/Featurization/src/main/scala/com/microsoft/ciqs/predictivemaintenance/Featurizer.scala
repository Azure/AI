package com.microsoft.ciqs.predictivemaintenance

import java.sql.Timestamp
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.ciqs.predictivemaintenance.Definitions._
import org.apache.spark.eventhubs.{ConnectionStringBuilder, EventHubsConf, EventPosition}
import org.apache.spark.sql.{ForeachWriter, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, OutputMode}
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row
import com.microsoft.azure.storage.table.{CloudTableClient, TableOperation, CloudTable, TableQuery}
import com.microsoft.azure.storage.table.TableQuery.QueryComparisons
import org.apache.spark.sql.expressions.Window
import scala.collection.JavaConverters._

object Featurizer {
  val PARTITION_KEY = "PartitionKey"
  val FAR_FUTURE_TIMESTAMP = new Timestamp(6284160000000L)
  val DEFAULT_CYCLE_GAP_MS = 30 * 1000 // (ms)

  def getIntervalsFromTimeSeries(timestamps: Iterator[Timestamp], cycleGapMs: Int) = {
    val first = timestamps.next()
    val augmented = Seq(first) ++ timestamps ++ Seq(FAR_FUTURE_TIMESTAMP)

    ((first, first) :: augmented.sliding(2)
      .map(x => (x(0), x(1), x(1).getTime - x(0).getTime))
      .filter(_._3 > cycleGapMs).map(x => (x._1, x._2)).toList)
      .sliding(2).map(x => List(x(0)._2, x(1)._1)).toList.reverse
  }

  def getCycleIntervalsStateful(machineID: String,
                                inputs: Iterator[TelemetryEvent],
                                groupState: GroupState[CycleInterval]): Iterator[CycleInterval] = {
    if (groupState.hasTimedOut) {
      assert(inputs.isEmpty)
      val state = groupState.get
      groupState.remove()
      Iterator(state)
    } else {
      val timestamps = inputs.map(x => x.timestamp)

      val latest :: tail = getIntervalsFromTimeSeries(timestamps, DEFAULT_CYCLE_GAP_MS)

      groupState.setTimeoutTimestamp(latest(1).getTime, "30 seconds")

      if (groupState.exists) {
        val state = groupState.get
        if (latest(0).getTime - state.end.getTime < DEFAULT_CYCLE_GAP_MS) {
          groupState.update(CycleInterval(state.start, latest(1), machineID))
          Iterator(groupState.get)
        } else {
          groupState.update(CycleInterval(latest(0), latest(1), machineID))
          Iterator(state)
        }
      } else {
        groupState.update(CycleInterval(latest(0), latest(1), machineID))
        tail.map(x => CycleInterval(x(0), x(1), machineID)).iterator
      }
    }
  }

  def main(args: Array[String]) {
    val endpoint = args(0)
    val eventHubName = args(1)
    val storageAccountConnectionString = args(2)

    val connectionString = ConnectionStringBuilder(endpoint)
     .setEventHubName(eventHubName)
     .build

    val spark = SparkSession
      .builder
      .appName("PredictiveMaintenanceFeaturizer").master("local[2]")
      .getOrCreate()

    val sc = spark.sparkContext
    sc.setLogLevel("WARN")

    import spark.implicits._

    val ehConf = EventHubsConf(connectionString)
      .setStartingPosition(EventPosition.fromStartOfStream)

    val schemaTyped = new StructType()
      //.add("timestamp", TimestampType)
      .add("ambient_pressure", DoubleType)
      .add("ambient_temperature", DoubleType)
      .add("machineID", StringType)
      .add("timestamp", TimestampType)
      .add("pressure", DoubleType)
      .add("speed", DoubleType)
      .add("speed_desired", LongType)
      .add("temperature", DoubleType)

    val telemetry = spark
      .readStream
      .format("eventhubs")
      .options(ehConf.toMap)
      .load()
      //.withColumn("timestamp", col("enqueuedTime"))
      .withColumn("BodyJ", from_json($"body".cast(StringType), schemaTyped))
      .select("*", "BodyJ.*")
      .withWatermark("timestamp", "1 days")
      .dropDuplicates()
      .as[TelemetryEvent]

    val telemetryByDevice = telemetry.withWatermark("timestamp", "30 seconds").groupByKey(_.machineID)

    val cycleIntervals = telemetryByDevice.
      flatMapGroupsWithState(
        outputMode = OutputMode.Append,
        timeoutConf = GroupStateTimeout.EventTimeTimeout)(func = getCycleIntervalsStateful).
      withColumnRenamed("machineID", "renamed_machineID").
      withWatermark("start", "1 days").
      withWatermark("end", "1 days")

    var cycleAggregates = cycleIntervals.
      join(telemetry,
        $"renamed_machineID" === $"machineID" &&
          $"start" <= $"timestamp" &&
          $"end" >= $"timestamp", "leftOuter").
      groupBy("machineID", "start", "end").
      agg(
        max("speed_desired").alias("SpeedDesiredMax"),
        avg("speed").alias("SpeedAvg"),
        avg("temperature").alias("TemperatureAvg"),
        max("temperature").alias("TemperatureMax"),
        avg("pressure").alias("PressureAvg"),
        max("pressure").alias("PressureMax"),
        min("timestamp").alias("CycleStart"),
        max("timestamp").alias("CycleEnd"),
        count(lit(1)).alias("RawCount")
      )

    val writer = new ForeachWriter[CycleAggregates] {
      var tableReference: CloudTable = _

      override def open(partitionId: Long, version: Long) = {
        val storageAccount =  CloudStorageAccount.parse(storageAccountConnectionString)
        val tableClient = storageAccount.createCloudTableClient
        tableReference = tableClient.getTableReference("cycles")
        true
      }
      override def process(value: CycleAggregates) = {
        if (value.getPartitionKey != null) {
          val insertCycleAggregates = TableOperation.insertOrReplace(value)
          tableReference.execute(insertCycleAggregates)
        }
      }
      override def close(errorOrNull: Throwable) = {}
    }

    val sq = cycleAggregates.
      as[CycleAggregates].
      writeStream.
      outputMode("update").
      foreach(writer).
      start()

    val storageAccount =  CloudStorageAccount.parse(storageAccountConnectionString)
    val tableClient = storageAccount.createCloudTableClient
    val cyclesTableReference = tableClient.getTableReference("cycles")
    val featuresTableReference = tableClient.getTableReference("features")
    val lookback = 5
    val w = Window.partitionBy("machineID").rowsBetween(-lookback, Window.currentRow).orderBy("CycleStart")


    while (true) {
      val machineID = "MACHINE-000"

      val partitionFilter = TableQuery.generateFilterCondition(
        PARTITION_KEY,
        QueryComparisons.EQUAL,
        machineID)

      val partitionQuery = TableQuery.from(classOf[CycleAggregates]).where(partitionFilter)

      val l = cyclesTableReference.execute(partitionQuery).asScala

      val df = sc.parallelize(l.toSeq).toDF

      val rollingAverages = Seq("TemperatureAvg", "TemperatureMax", "PressureAvg", "PressureMax")

      val augmented_labeled_cycles_df = rollingAverages.foldLeft(df){
        (_df, colName) => _df.withColumn(colName.concat("RollingAvg"), avg(colName).over(w))
      }.orderBy(desc("CycleStart")).limit(1).drop( "RawCount")

      var nonFeatureColumns = Set("MachineID", "CycleStart", "CycleEnd")
      val featureColumns = augmented_labeled_cycles_df.columns.filterNot(c => nonFeatureColumns.contains(c))

      val obfuscateColumns = featureColumns zip (1 to featureColumns.length + 1)

      val features_df = obfuscateColumns.foldLeft(augmented_labeled_cycles_df){
        (_df, c) => _df.withColumnRenamed(c._1, "s".concat(c._2.toString))
      }.as[Features]

      if (features_df.count() > 0) {

        val featuresJson = features_df.drop(nonFeatureColumns.toList: _*).toJSON.first()

        val features = features_df.first()
        features.FeaturesJson = featuresJson

        val insertFeatures = TableOperation.insertOrReplace(features)
        featuresTableReference.execute(insertFeatures)
      }

      sq.awaitTermination(5000)
    }
  }
}
