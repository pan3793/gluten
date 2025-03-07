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

package io.glutenproject.expression

import org.apache.spark.sql.catalyst.expressions.PythonUDF
import org.apache.spark.internal.Logging

import io.glutenproject.substrait.expression.ExpressionNode
import io.glutenproject.substrait.expression.ExpressionBuilder

import java.util.ArrayList
case class PythonUDFTransformer(
    substraitExprName: String,
    children: Seq[ExpressionTransformer],
    original: PythonUDF)
  extends ExpressionTransformer
  with Logging {
  override def doTransform(args: java.lang.Object): ExpressionNode = {
    val functionMap = args.asInstanceOf[java.util.HashMap[String, java.lang.Long]]
    val functionId = ExpressionBuilder.newScalarFunction(
      functionMap,
      ConverterUtils.makeFuncName(
        substraitExprName,
        original.children.map(_.dataType),
        ConverterUtils.FunctionConfig.OPT))

    val expressionNodes = new ArrayList[ExpressionNode]
    children.foreach(child => expressionNodes.add(child.doTransform(args)))

    val typeNode = ConverterUtils.getTypeNode(original.dataType, original.nullable)
    ExpressionBuilder.makeScalarFunction(functionId, expressionNodes, typeNode)
  }
}