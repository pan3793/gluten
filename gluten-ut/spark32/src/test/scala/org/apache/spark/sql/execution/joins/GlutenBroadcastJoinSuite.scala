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

package org.apache.spark.sql.execution.joins

import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.GlutenConfig
import io.glutenproject.execution.{BroadcastHashJoinExecTransformer, ColumnarToRowExecBase, WholeStageTransformer}
import io.glutenproject.utils.SystemParameters
import org.apache.spark.sql.GlutenTestConstants.GLUTEN_TEST
import org.apache.spark.sql.{GlutenTestsCommonTrait, SparkSession}
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight, BuildSide, ConstantFolding, ConvertToLocalRelation, NullPropagation}
import org.apache.spark.sql.execution.exchange.EnsureRequirements
import org.apache.spark.sql.functions.broadcast
import org.apache.spark.sql.internal.SQLConf

class GlutenBroadcastJoinSuite extends BroadcastJoinSuite with GlutenTestsCommonTrait {

  import testImplicits._

  /**
   * Create a new [[SparkSession]] running in local-cluster mode with unsafe and codegen enabled.
   */

  private val EnsureRequirements = new EnsureRequirements()

  // BroadcastHashJoinExecTransformer is not case class, can't call toString method,
  // let's put constant string here.
  private val bh = "BroadcastHashJoinExecTransformer"
  private val bl = BroadcastNestedLoopJoinExec.toString

  override def beforeAll(): Unit = {
    super.beforeAll()

    // stop sparkSession created by parent class,
    // and create a new one with Gluten enabled.
    spark.stop()
    spark = null
    
    val sparkBuilder = SparkSession.builder()
      .master("local-cluster[2,1,1024]")
      .appName("Gluten-UT")
      .config(SQLConf.OPTIMIZER_EXCLUDED_RULES.key, ConvertToLocalRelation.ruleName)
      .config("spark.driver.memory", "1G")
      .config("spark.sql.adaptive.enabled", "true")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.files.maxPartitionBytes", "134217728")
      .config("spark.memory.offHeap.enabled", "true")
      .config("spark.memory.offHeap.size", "1024MB")
      .config("spark.plugins", "io.glutenproject.GlutenPlugin")
      .config("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
      .config("spark.sql.warehouse.dir", warehouse)
      // Avoid static evaluation for literal input by spark catalyst.
      .config("spark.sql.optimizer.excludedRules", ConstantFolding.ruleName + "," +
        NullPropagation.ruleName)
      // Avoid the code size overflow error in Spark code generation.
      .config("spark.sql.codegen.wholeStage", "false")

    spark = if (BackendsApiManager.getBackendName.equalsIgnoreCase(
      GlutenConfig.GLUTEN_CLICKHOUSE_BACKEND)) {
      sparkBuilder
        .config("spark.io.compression.codec", "LZ4")
        .config("spark.gluten.sql.columnar.backend.ch.worker.id", "1")
        .config("spark.gluten.sql.columnar.backend.ch.use.v2", "false")
        .config("spark.gluten.sql.enable.native.validation", "false")
        .config("spark.sql.files.openCostInBytes", "134217728")
        .config(GlutenConfig.GLUTEN_LIB_PATH, SystemParameters.getClickHouseLibPath)
        .config("spark.unsafe.exceptionOnMemoryLeak", "true")
        .getOrCreate()
    } else {
      sparkBuilder
        .config("spark.unsafe.exceptionOnMemoryLeak", "true")
        .getOrCreate()
    }
  }

  // === Following cases override super class's cases ===

  test(GLUTEN_TEST + "Shouldn't change broadcast join buildSide if user clearly specified") {
    withTempView("t1", "t2") {
      Seq((1, "4"), (2, "2")).toDF("key", "value").createTempView("t1")
      Seq((1, "1"), (2, "12.3"), (2, "123")).toDF("key", "value").createTempView("t2")

      val t1Size = spark.table("t1").queryExecution.analyzed.children.head.stats.sizeInBytes
      val t2Size = spark.table("t2").queryExecution.analyzed.children.head.stats.sizeInBytes
      assert(t1Size < t2Size)

      /* ######## test cases for equal join ######### */
      // INNER JOIN && t1Size < t2Size => BuildLeft
      assertJoinBuildSide(
        "SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 JOIN t2 ON t1.key = t2.key", bh, BuildLeft)
      // LEFT JOIN => BuildRight
      // broadcast hash join can not build left side for left join.
      assertJoinBuildSide(
        "SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 LEFT JOIN t2 ON t1.key = t2.key", bh, BuildRight)
      // RIGHT JOIN => BuildLeft
      // broadcast hash join can not build right side for right join.
      assertJoinBuildSide(
        "SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 RIGHT JOIN t2 ON t1.key = t2.key", bh, BuildLeft)
      // INNER JOIN && broadcast(t1) => BuildLeft
      assertJoinBuildSide(
        "SELECT /*+ MAPJOIN(t1) */ * FROM t1 JOIN t2 ON t1.key = t2.key", bh, BuildLeft)
      // INNER JOIN && broadcast(t2) => BuildRight
      assertJoinBuildSide(
        "SELECT /*+ MAPJOIN(t2) */ * FROM t1 JOIN t2 ON t1.key = t2.key", bh, BuildRight)

      /* ######## test cases for non-equal join ######### */
      withSQLConf(SQLConf.CROSS_JOINS_ENABLED.key -> "true") {
        // INNER JOIN && t1Size < t2Size => BuildLeft
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 JOIN t2", bl, BuildLeft)
        // FULL JOIN && t1Size < t2Size => BuildLeft
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 FULL JOIN t2", bl, BuildLeft)
        // FULL OUTER && t1Size < t2Size => BuildLeft
        assertJoinBuildSide("SELECT * FROM t1 FULL OUTER JOIN t2", bl, BuildLeft)
        // LEFT JOIN => BuildRight
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 LEFT JOIN t2", bl, BuildRight)
        // RIGHT JOIN => BuildLeft
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1, t2) */ * FROM t1 RIGHT JOIN t2", bl, BuildLeft)

        /* #### test with broadcast hint #### */
        // INNER JOIN && broadcast(t1) => BuildLeft
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1) */ * FROM t1 JOIN t2", bl, BuildLeft)
        // INNER JOIN && broadcast(t2) => BuildRight
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t2) */ * FROM t1 JOIN t2", bl, BuildRight)
        // FULL OUTER && broadcast(t1) => BuildLeft
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1) */ * FROM t1 FULL OUTER JOIN t2", bl, BuildLeft)
        // FULL OUTER && broadcast(t2) => BuildRight
        assertJoinBuildSide(
          "SELECT /*+ MAPJOIN(t2) */ * FROM t1 FULL OUTER JOIN t2", bl, BuildRight)
        // LEFT JOIN && broadcast(t1) => BuildLeft
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t1) */ * FROM t1 LEFT JOIN t2", bl, BuildLeft)
        // RIGHT JOIN && broadcast(t2) => BuildRight
        assertJoinBuildSide("SELECT /*+ MAPJOIN(t2) */ * FROM t1 RIGHT JOIN t2", bl, BuildRight)
      }
    }
  }

  test(GLUTEN_TEST + "Shouldn't bias towards build right if user didn't specify") {

    withTempView("t1", "t2") {
      Seq((1, "4"), (2, "2")).toDF("key", "value").createTempView("t1")
      Seq((1, "1"), (2, "12.3"), (2, "123")).toDF("key", "value").createTempView("t2")

      val t1Size = spark.table("t1").queryExecution.analyzed.children.head.stats.sizeInBytes
      val t2Size = spark.table("t2").queryExecution.analyzed.children.head.stats.sizeInBytes
      assert(t1Size < t2Size)

      /* ######## test cases for equal join ######### */
      assertJoinBuildSide("SELECT * FROM t1 JOIN t2 ON t1.key = t2.key", bh, BuildLeft)
      assertJoinBuildSide("SELECT * FROM t2 JOIN t1 ON t1.key = t2.key", bh, BuildRight)

      assertJoinBuildSide("SELECT * FROM t1 LEFT JOIN t2 ON t1.key = t2.key", bh, BuildRight)
      assertJoinBuildSide("SELECT * FROM t2 LEFT JOIN t1 ON t1.key = t2.key", bh, BuildRight)

      assertJoinBuildSide("SELECT * FROM t1 RIGHT JOIN t2 ON t1.key = t2.key", bh, BuildLeft)
      assertJoinBuildSide("SELECT * FROM t2 RIGHT JOIN t1 ON t1.key = t2.key", bh, BuildLeft)

      /* ######## test cases for non-equal join ######### */
      withSQLConf(SQLConf.CROSS_JOINS_ENABLED.key -> "true") {
        // For full outer join, prefer to broadcast the smaller side.
        assertJoinBuildSide("SELECT * FROM t1 FULL OUTER JOIN t2", bl, BuildLeft)
        assertJoinBuildSide("SELECT * FROM t2 FULL OUTER JOIN t1", bl, BuildRight)

        // For inner join, prefer to broadcast the smaller side, if broadcast-able.
        withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> (t2Size + 1).toString()) {
          assertJoinBuildSide("SELECT * FROM t1 JOIN t2", bl, BuildLeft)
          assertJoinBuildSide("SELECT * FROM t2 JOIN t1", bl, BuildRight)
        }

        // For left join, prefer to broadcast the right side.
        assertJoinBuildSide("SELECT * FROM t1 LEFT JOIN t2", bl, BuildRight)
        assertJoinBuildSide("SELECT * FROM t2 LEFT JOIN t1", bl, BuildRight)

        // For right join, prefer to broadcast the left side.
        assertJoinBuildSide("SELECT * FROM t1 RIGHT JOIN t2", bl, BuildLeft)
        assertJoinBuildSide("SELECT * FROM t2 RIGHT JOIN t1", bl, BuildLeft)
      }
    }
  }

  test(GLUTEN_TEST + "SPARK-23192: broadcast hint should be retained after using the cached data") {
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      try {
        val df1 = Seq((1, "4"), (2, "2")).toDF("key", "value")
        val df2 = Seq((1, "1"), (2, "2")).toDF("key", "value")
        df2.cache()
        val df3 = df1.join(broadcast(df2), Seq("key"), "inner")
        val numBroadCastHashJoin = collect(df3.queryExecution.executedPlan) {
          case b: BroadcastHashJoinExecTransformer => b
        }.size
        assert(numBroadCastHashJoin === 1)
      } finally {
        spark.catalog.clearCache()
      }
    }
  }

  test(GLUTEN_TEST + "broadcast hint isn't propagated after a join") {
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      val df1 = Seq((1, "4"), (2, "2")).toDF("key", "value")
      val df2 = Seq((1, "1"), (2, "2")).toDF("key", "value")
      val df3 = df1.join(broadcast(df2), Seq("key"), "inner").drop(df2("key"))

      val df4 = Seq((1, "5"), (2, "5")).toDF("key", "value")
      val df5 = df4.join(df3, Seq("key"), "inner")

      val plan = EnsureRequirements.apply(df5.queryExecution.sparkPlan)

      assert(plan.collect { case p: BroadcastHashJoinExec => p }.size === 1)
      assert(plan.collect { case p: ShuffledHashJoinExec => p }.size === 1)
    }
  }

  private def assertJoinBuildSide(sqlStr: String, joinMethod: String, buildSide: BuildSide): Any = {
    val executedPlan = stripAQEPlan(sql(sqlStr).queryExecution.executedPlan)
    executedPlan match {
      case c2r: ColumnarToRowExecBase =>
        c2r.child match {
          case w: WholeStageTransformer =>
            val join = w.child match {
              case b: BroadcastHashJoinExecTransformer => b
            }
            assert(join.getClass.getSimpleName.endsWith(joinMethod))
            assert(join.joinBuildSide == buildSide)
        }
      case b: BroadcastNestedLoopJoinExec =>
        assert(b.getClass.getSimpleName === joinMethod)
        assert(b.buildSide === buildSide)
    }
  }
}
