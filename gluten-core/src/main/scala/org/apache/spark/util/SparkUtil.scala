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
package org.apache.spark.util

import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.rules.Rule

object SparkUtil extends Logging {

  /**
   * Add the extended pre/post column rules
   */
  def extendedColumnarRules(
                             session: SparkSession,
                             conf: String
                           ): List[SparkSession => Rule[SparkPlan]] = {
    val extendedRules = conf.split(",").filter(!_.isEmpty)
    extendedRules.map { ruleStr =>
      try {
        val extensionConfClass = Utils.classForName(ruleStr)
        val extensionConf =
          extensionConfClass.getConstructor(classOf[SparkSession]).newInstance(session)
            .asInstanceOf[Rule[SparkPlan]]

        Some((sparkSession: SparkSession) => extensionConf)
      } catch {
        // Ignore the error if we cannot find the class or when the class has the wrong type.
        case e@(_: ClassCastException |
                _: ClassNotFoundException |
                _: NoClassDefFoundError) =>
          logWarning(s"Cannot create extended rule $ruleStr", e)
        None
      }
    }.filter(!_.isEmpty).map(_.get).toList
  }
}
