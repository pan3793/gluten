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

import io.glutenproject.execution.BroadCastHashJoinContext

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.vectorized.ColumnarBatch

trait BuildSideRelation extends Serializable {

  /**
   * Deserialized relation from broadcasted value
   */
  def deserialized: Iterator[ColumnarBatch]

  /**
   * Transform columnar broadcasted value to Array[InternalRow] by key and distinct.
   * @return
   */
  def transform(key: Expression): Array[InternalRow]

  /**
   * Returns a read-only copy of this, to be safely used in current thread.
   */
  def asReadOnlyCopy(broadCastContext: BroadCastHashJoinContext): BuildSideRelation
}
