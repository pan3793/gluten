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
package io.glutenproject.execution

import io.glutenproject.execution.CHHashAggregateExecTransformer.getAggregateResultAttributes
import io.glutenproject.expression._
import io.glutenproject.substrait.`type`.{TypeBuilder, TypeNode}
import io.glutenproject.substrait.{AggregationParams, SubstraitContext}
import io.glutenproject.substrait.expression.{AggregateFunctionNode, ExpressionBuilder, ExpressionNode}
import io.glutenproject.substrait.extensions.ExtensionBuilder
import io.glutenproject.substrait.rel.{LocalFilesBuilder, RelBuilder, RelNode}

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.aggregate.BaseAggregateExec
import org.apache.spark.sql.types.{DataType, StructField, StructType}

import com.google.protobuf.Any

import java.util

object CHHashAggregateExecTransformer {
  def getAggregateResultAttributes(
      groupingExpressions: Seq[NamedExpression],
      aggregateExpressions: Seq[AggregateExpression]): Seq[Attribute] = {
    val groupingAttributes = groupingExpressions.map(
      expr => {
        ConverterUtils.getAttrFromExpr(expr).toAttribute
      })
    groupingAttributes ++ aggregateExpressions.map(
      expr => {
        expr.resultAttribute
      })
  }

  private val curId = new java.util.concurrent.atomic.AtomicLong()
  def newStructFieldId(): Long = {
    curId.getAndIncrement()
  }
}

case class CHHashAggregateExecTransformer(
    requiredChildDistributionExpressions: Option[Seq[Expression]],
    groupingExpressions: Seq[NamedExpression],
    aggregateExpressions: Seq[AggregateExpression],
    aggregateAttributes: Seq[Attribute],
    initialInputBufferOffset: Int,
    resultExpressions: Seq[NamedExpression],
    child: SparkPlan)
  extends HashAggregateExecBaseTransformer(
    requiredChildDistributionExpressions,
    groupingExpressions,
    aggregateExpressions,
    aggregateAttributes,
    initialInputBufferOffset,
    resultExpressions,
    child) {

  lazy val aggregateResultAttributes =
    getAggregateResultAttributes(groupingExpressions, aggregateExpressions)

  protected val modes: Seq[AggregateMode] = aggregateExpressions.map(_.mode).distinct

  override def doTransform(context: SubstraitContext): TransformContext = {
    val childCtx = child match {
      case c: TransformSupport =>
        c.doTransform(context)
      case _ =>
        null
    }

    val aggParams = new AggregationParams
    val operatorId = context.nextOperatorId(this.nodeName)

    val (relNode, inputAttributes, outputAttributes) = if (childCtx != null) {
      // The final HashAggregateExecTransformer and partial HashAggregateExecTransformer
      // are in the one WholeStageTransformer.
      if (!modes.contains(Partial)) {
        (
          getAggRel(context, operatorId, aggParams, childCtx.root),
          childCtx.outputAttributes,
          output)
      } else {
        (
          getAggRel(context, operatorId, aggParams, childCtx.root),
          childCtx.outputAttributes,
          aggregateResultAttributes)
      }
    } else {
      // This means the input is just an iterator, so an ReadRel will be created as child.
      // Prepare the input schema.
      // Notes: Currently, ClickHouse backend uses the output attributes of
      // aggregateResultAttributes as Shuffle output,
      // which is different from Velox backend.
      aggParams.isReadRel = true
      val typeList = new util.ArrayList[TypeNode]()
      val nameList = new util.ArrayList[String]()
      val (inputAttrs, outputAttrs) =
        if (!modes.contains(Partial)) {
          for (attr <- aggregateResultAttributes) {
            val colName = if (aggregateAttributes.contains(attr)) {
              // for aggregate func
              ConverterUtils.genColumnNameWithExprId(attr) +
                "#Partial#" + ConverterUtils.getShortAttributeName(attr)
            } else {
              // for group by cols
              ConverterUtils.genColumnNameWithExprId(attr)
            }
            nameList.add(colName)
            val (dataType, nullable) =
              getIntermediateAggregateResultType(attr, aggregateExpressions)
            nameList.addAll(RelBuilder.collectStructFieldNamesDFS(dataType))
            typeList.add(ConverterUtils.getTypeNode(dataType, nullable))
          }
          (aggregateResultAttributes, output)
        } else {
          for (attr <- child.output) {
            typeList.add(ConverterUtils.getTypeNode(attr.dataType, attr.nullable))
            nameList.add(ConverterUtils.genColumnNameWithExprId(attr))
          }

          (child.output, aggregateResultAttributes)
        }

      // The iterator index will be added in the path of LocalFiles.
      val iteratorIndex: Long = context.nextIteratorIndex
      val inputIter = LocalFilesBuilder.makeLocalFiles(
        ConverterUtils.ITERATOR_PREFIX.concat(iteratorIndex.toString))
      context.setIteratorNode(iteratorIndex, inputIter)
      val readRel =
        RelBuilder.makeReadRel(typeList, nameList, null, iteratorIndex, context, operatorId)

      (getAggRel(context, operatorId, aggParams, readRel), inputAttrs, outputAttrs)
    }
    TransformContext(inputAttributes, outputAttributes, relNode)
  }

  override def getAggRel(
      context: SubstraitContext,
      operatorId: Long,
      aggParams: AggregationParams,
      input: RelNode = null,
      validation: Boolean = false): RelNode = {
    val originalInputAttributes = child.output
    val aggRel = if (needsPreProjection) {
      aggParams.preProjectionNeeded = true
      getAggRelWithPreProjection(context, originalInputAttributes, operatorId, input, validation)
    } else {
      getAggRelWithoutPreProjection(
        context,
        aggregateResultAttributes,
        operatorId,
        input,
        validation)
    }
    // Will check if post-projection is needed. If yes, a ProjectRel will be added after the
    // AggregateRel.
    val resRel = if (!needsPostProjection(allAggregateResultAttributes)) {
      aggRel
    } else {
      aggParams.postProjectionNeeded = true
      applyPostProjection(context, aggRel, operatorId, validation)
    }
    context.registerAggregationParam(operatorId, aggParams)
    resRel
  }

  override def getAggRelWithoutPreProjection(
      context: SubstraitContext,
      originalInputAttributes: Seq[Attribute],
      operatorId: Long,
      input: RelNode = null,
      validation: Boolean): RelNode = {
    val args = context.registeredFunction
    // Get the grouping nodes.
    val groupingList = new util.ArrayList[ExpressionNode]()
    groupingExpressions.foreach(
      expr => {
        // Use 'child.output' as based Seq[Attribute], the originalInputAttributes
        // may be different for each backend.
        val exprNode = ExpressionConverter
          .replaceWithExpressionTransformer(expr, child.output)
          .doTransform(args)
        groupingList.add(exprNode)
      })
    // Get the aggregate function nodes.
    val aggregateFunctionList = new util.ArrayList[AggregateFunctionNode]()

    val distinct_modes = aggregateExpressions.map(_.mode).distinct
    // quick check
    if (distinct_modes.contains(PartialMerge)) {
      if (distinct_modes.contains(Final)) {
        throw new IllegalStateException("PartialMerge co-exists with Final")
      }
    }

    val aggFilterList = new util.ArrayList[ExpressionNode]()
    aggregateExpressions.foreach(
      aggExpr => {
        if (aggExpr.filter.isDefined) {
          val exprNode = ExpressionConverter
            .replaceWithExpressionTransformer(aggExpr.filter.get, child.output)
            .doTransform(args)
          aggFilterList.add(exprNode)
        } else {
          aggFilterList.add(null)
        }

        val aggregateFunc = aggExpr.aggregateFunction
        val childrenNodeList = new util.ArrayList[ExpressionNode]()
        val childrenNodes = aggExpr.mode match {
          case Partial =>
            aggregateFunc.children.toList.map(
              expr => {
                ExpressionConverter
                  .replaceWithExpressionTransformer(expr, child.output)
                  .doTransform(args)
              })
          case PartialMerge if distinct_modes.contains(Partial) =>
            // this is the case where PartialMerge co-exists with Partial
            // so far, it only happens in a three-stage count distinct case
            // e.g. select sum(a), count(distinct b) from f
            if (!child.isInstanceOf[BaseAggregateExec]) {
              throw new UnsupportedOperationException(
                "PartialMerge's child not being HashAggregateExecBaseTransformer" +
                  " is unsupported yet")
            }
            val aggTypesExpr = ExpressionConverter
              .replaceWithExpressionTransformer(
                aggExpr.resultAttribute,
                CHHashAggregateExecTransformer.getAggregateResultAttributes(
                  child.asInstanceOf[BaseAggregateExec].groupingExpressions,
                  child.asInstanceOf[BaseAggregateExec].aggregateExpressions)
              )
            Seq(aggTypesExpr.doTransform(args))
          case Final | PartialMerge =>
            Seq(
              ExpressionConverter
                .replaceWithExpressionTransformer(aggExpr.resultAttribute, originalInputAttributes)
                .doTransform(args))
          case other =>
            throw new UnsupportedOperationException(s"$other not supported.")
        }
        for (node <- childrenNodes) {
          childrenNodeList.add(node)
        }
        val aggFunctionNode = ExpressionBuilder.makeAggregateFunction(
          AggregateFunctionsBuilder.create(args, aggregateFunc),
          childrenNodeList,
          modeToKeyWord(aggExpr.mode),
          ConverterUtils.getTypeNode(aggregateFunc.dataType, aggregateFunc.nullable)
        )
        aggregateFunctionList.add(aggFunctionNode)
      })
    if (!validation) {
      RelBuilder.makeAggregateRel(
        input,
        groupingList,
        aggregateFunctionList,
        aggFilterList,
        context,
        operatorId)
    } else {
      // Use a extension node to send the input types through Substrait plan for validation.
      val inputTypeNodeList = new java.util.ArrayList[TypeNode]()
      for (attr <- originalInputAttributes) {
        inputTypeNodeList.add(ConverterUtils.getTypeNode(attr.dataType, attr.nullable))
      }
      val extensionNode = ExtensionBuilder.makeAdvancedExtension(
        Any.pack(TypeBuilder.makeStruct(false, inputTypeNodeList).toProtobuf))
      RelBuilder.makeAggregateRel(
        input,
        groupingList,
        aggregateFunctionList,
        aggFilterList,
        extensionNode,
        context,
        operatorId)
    }
  }

  override def isStreaming: Boolean = false

  def numShufflePartitions: Option[Int] = Some(0)

  def getIntermediateAggregateResultType(
      attr: Attribute,
      inputAggregateExpressions: Seq[AggregateExpression]): (DataType, Boolean) = {
    def makeStructType(types: Seq[(DataType, Boolean)]): StructType = {
      val fields = new Array[StructField](types.length)
      var i = 0
      types.foreach {
        case (dataType, nullable) =>
          fields.update(
            i,
            new StructField(
              s"anonymousField${CHHashAggregateExecTransformer.newStructFieldId()}",
              dataType,
              nullable))
          i += 1
      }
      StructType(fields)
    }

    def makeStructTypeSingleOne(dataType: DataType, nullable: Boolean): StructType = {
      makeStructType(Seq[(DataType, Boolean)]((dataType, nullable)))
    }

    val aggregateExpression = inputAggregateExpressions.find(_.resultAttribute == attr)
    // We need a way to represent the intermediate result's type of the aggregate function.
    // substrait only contains basic types, we make a trick here.
    //   1. the intermediate result column will has a special format name,
    //     see genPartialAggregateResultColumnName
    //   2. Use a struct type to wrap the arguments' types of the aggregate function.
    val (dataType, nullable) = if (!aggregateExpression.isDefined) {
      (attr.dataType, attr.nullable)
    } else {
      aggregateExpression.get match {
        case aggExpr: AggregateExpression =>
          aggExpr.aggregateFunction match {
            case avg: Average =>
              (makeStructTypeSingleOne(avg.child.dataType, attr.nullable), attr.nullable)
            case collectList: CollectList =>
              // Be careful with the nullable. We must keep the nullable the same as the column
              // otherwise it will cause a parsing exception on partial aggregated data.
              (
                makeStructTypeSingleOne(collectList.child.dataType, collectList.child.nullable),
                collectList.child.nullable
              )
            case covar: Covariance =>
              var fields = Seq[(DataType, Boolean)]()
              fields = fields :+ (covar.left.dataType, covar.left.nullable)
              fields = fields :+ (covar.right.dataType, covar.right.nullable)
              (makeStructType(fields), attr.nullable)
            case expr =>
              (makeStructTypeSingleOne(attr.dataType, attr.nullable), attr.nullable)
          }
        case expr =>
          (attr.dataType, attr.nullable)
      }
    }
    (dataType, nullable)
  }

  override protected def withNewChildInternal(
      newChild: SparkPlan): CHHashAggregateExecTransformer = {
    copy(child = newChild)
  }
}
