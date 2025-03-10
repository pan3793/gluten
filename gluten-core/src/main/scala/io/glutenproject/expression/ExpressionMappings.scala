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

import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.expression.ExpressionNames._
import io.glutenproject.sql.shims.SparkShimLoader

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.optimizer.NormalizeNaNAndZero
import org.apache.spark.sql.execution.ScalarSubquery

object ExpressionMappings {

  /** Mapping Spark scalar expression to Substrait function name */
  private val SCALAR_SIGS: Seq[Sig] = Seq(
    Sig[Add](ADD),
    Sig[Asinh](ASINH),
    Sig[Acosh](ACOSH),
    Sig[Atanh](ATANH),
    Sig[Subtract](SUBTRACT),
    Sig[Multiply](MULTIPLY),
    Sig[Divide](DIVIDE),
    Sig[And](AND),
    Sig[Or](OR),
    Sig[Cast](CAST),
    Sig[Coalesce](COALESCE),
    Sig[Like](LIKE),
    Sig[RLike](RLIKE),
    Sig[RegExpReplace](REGEXP_REPLACE),
    Sig[RegExpExtract](REGEXP_EXTRACT),
    Sig[RegExpExtractAll](REGEXP_EXTRACT_ALL),
    Sig[EqualTo](EQUAL),
    Sig[LessThan](LESS_THAN),
    Sig[LessThanOrEqual](LESS_THAN_OR_EQUAL),
    Sig[GreaterThan](GREATER_THAN),
    Sig[GreaterThanOrEqual](GREATER_THAN_OR_EQUAL),
    Sig[Alias](ALIAS),
    Sig[IsNotNull](IS_NOT_NULL),
    Sig[IsNull](IS_NULL),
    Sig[Not](NOT),
    Sig[IsNaN](IS_NAN),
    // SparkSQL String functions
    Sig[Ascii](ASCII),
    Sig[Chr](CHR),
    Sig[Extract](EXTRACT),
    Sig[EndsWith](ENDS_WITH),
    Sig[StartsWith](STARTS_WITH),
    Sig[Concat](CONCAT),
    Sig[Contains](CONTAINS),
    Sig[StringInstr](INSTR),
    Sig[Length](LENGTH),
    Sig[Lower](LOWER),
    Sig[Upper](UPPER),
    Sig[StringLocate](LOCATE),
    Sig[StringTrimLeft](LTRIM),
    Sig[StringTrimRight](RTRIM),
    Sig[StringTrim](TRIM),
    Sig[StringLPad](LPAD),
    Sig[StringRPad](RPAD),
    Sig[StringReplace](REPLACE),
    Sig[Reverse](REVERSE),
    Sig[StringSplit](SPLIT),
    Sig[Substring](SUBSTRING),
    Sig[ConcatWs](CONCAT_WS),
    Sig[StringRepeat](REPEAT),
    Sig[StringTranslate](TRANSLATE),
    Sig[StringSpace](SPACE),
    // SparkSQL Math functions
    Sig[Abs](ABS),
    Sig[Ceil](CEIL),
    Sig[Floor](FLOOR),
    Sig[Exp](EXP),
    Sig[Pow](POWER),
    Sig[Pmod](PMOD),
    Sig[Round](ROUND),
    Sig[BRound](BROUND),
    Sig[Sin](SIN),
    Sig[Sinh](SINH),
    Sig[Tan](TAN),
    Sig[Tanh](TANH),
    Sig[BitwiseNot](BITWISE_NOT),
    Sig[BitwiseAnd](BITWISE_AND),
    Sig[BitwiseOr](BITWISE_OR),
    Sig[BitwiseXor](BITWISE_XOR),
    Sig[ShiftLeft](SHIFTLEFT),
    Sig[ShiftRight](SHIFTRIGHT),
    Sig[Sqrt](SQRT),
    Sig[Cbrt](CBRT),
    Sig[EulerNumber](E),
    Sig[Pi](PI),
    Sig[Hex](HEX),
    Sig[Unhex](UNHEX),
    Sig[Hypot](HYPOT),
    Sig[Signum](SIGN),
    Sig[Log1p](LOG1P),
    Sig[Log2](LOG2),
    Sig[Log](LOG),
    Sig[ToRadians](RADIANS),
    Sig[Greatest](GREATEST),
    Sig[Least](LEAST),
    Sig[Remainder](REMAINDER),
    Sig[Factorial](FACTORIAL),
    Sig[Rand](RAND),
    // PrestoSQL Math functions
    Sig[Acos](ACOS),
    Sig[Asin](ASIN),
    Sig[Atan](ATAN),
    Sig[Atan2](ATAN2),
    Sig[Cos](COS),
    Sig[Cosh](COSH),
    Sig[Log10](LOG10),
    Sig[ToDegrees](DEGREES),
    // SparkSQL DateTime functions
    Sig[Year](EXTRACT),
    Sig[YearOfWeek](EXTRACT),
    Sig[Quarter](EXTRACT),
    Sig[Month](EXTRACT),
    Sig[WeekOfYear](EXTRACT),
    Sig[WeekDay](EXTRACT),
    Sig[DayOfWeek](EXTRACT),
    Sig[DayOfMonth](EXTRACT),
    Sig[DayOfYear](EXTRACT),
    Sig[Hour](EXTRACT),
    Sig[Minute](EXTRACT),
    Sig[Second](EXTRACT),
    Sig[FromUnixTime](FROM_UNIXTIME),
    Sig[DateAdd](DATE_ADD),
    Sig[DateSub](DATE_SUB),
    Sig[DateDiff](DATE_DIFF),
    Sig[ToUnixTimestamp](TO_UNIX_TIMESTAMP),
    Sig[UnixTimestamp](UNIX_TIMESTAMP),
    Sig[AddMonths](ADD_MONTHS),
    Sig[DateFormatClass](DATE_FORMAT),
    Sig[TruncDate](TRUNC),
    Sig[GetTimestamp](GET_TIMESTAMP),
    // JSON functions
    Sig[GetJsonObject](GET_JSON_OBJECT),
    Sig[LengthOfJsonArray](JSON_ARRAY_LENGTH),
    Sig[StructsToJson](TO_JSON),
    Sig[JsonToStructs](FROM_JSON),
    Sig[JsonTuple](JSON_TUPLE),
    // Hash functions
    Sig[Murmur3Hash](MURMUR3HASH),
    Sig[XxHash64](XXHASH64),
    Sig[Md5](MD5),
    Sig[Sha1](SHA1),
    Sig[Sha2](SHA2),
    Sig[Crc32](CRC32),
    // Array functions
    Sig[Size](SIZE),
    Sig[CreateArray](CREATE_ARRAY),
    Sig[Explode](EXPLODE),
    Sig[PosExplode](POSEXPLODE),
    Sig[GetArrayItem](GET_ARRAY_ITEM),
    Sig[ElementAt](ELEMENT_AT),
    Sig[ArrayContains](ARRAY_CONTAINS),
    Sig[ArrayMax](ARRAY_MAX),
    Sig[ArrayMin](ARRAY_MIN),
    Sig[Sequence](SEQUENCE),
    Sig[SortArray](SORT_ARRAY),
    Sig[ArraysOverlap](ARRAYS_OVERLAP),
    Sig[Slice](SLICE),

    // Map functions
    Sig[CreateMap](CREATE_MAP),
    Sig[GetMapValue](GET_MAP_VALUE),
    Sig[MapKeys](MAP_KEYS),
    Sig[MapValues](MAP_VALUES),
    Sig[MapFromArrays](MAP_FROM_ARRAYS),
    // Struct functions
    Sig[GetStructField](GET_STRUCT_FIELD),
    Sig[CreateNamedStruct](NAMED_STRUCT),
    // Directly use child expression transformer
    Sig[KnownFloatingPointNormalized](KNOWN_FLOATING_POINT_NORMALIZED),
    Sig[NormalizeNaNAndZero](NORMALIZE_NANAND_ZERO),
    // Specific expression
    Sig[If](IF),
    Sig[AttributeReference](ATTRIBUTE_REFERENCE),
    Sig[BoundReference](BOUND_REFERENCE),
    Sig[Literal](LITERAL),
    Sig[CaseWhen](CASE_WHEN),
    Sig[In](IN),
    Sig[InSet](IN_SET),
    Sig[ScalarSubquery](SCALAR_SUBQUERY),
    Sig[CheckOverflow](CHECK_OVERFLOW),
    Sig[MakeDecimal](MAKE_DECIMAL),
    Sig[PromotePrecision](PROMOTE_PRECISION),
    // Decimal
    Sig[UnscaledValue](UNSCALED_VALUE)
  ) ++ SparkShimLoader.getSparkShims.expressionMappings

  /** Mapping Spark aggregation expression to Substrait function name */
  private val AGGREGATE_SIGS: Seq[Sig] = Seq(
    Sig[Sum](SUM),
    Sig[Average](AVG),
    Sig[Count](COUNT),
    Sig[Min](MIN),
    Sig[Max](MAX),
    Sig[StddevSamp](STDDEV_SAMP),
    Sig[StddevPop](STDDEV_POP),
    Sig[CollectList](COLLECT_LIST),
    Sig[VarianceSamp](VAR_SAMP),
    Sig[VariancePop](VAR_POP),
    Sig[BitAndAgg](BIT_AND_AGG),
    Sig[BitOrAgg](BIT_OR_AGG),
    Sig[BitXorAgg](BIT_XOR_AGG),
    Sig[Corr](CORR),
    Sig[CovPopulation](COVAR_POP),
    Sig[CovSample](COVAR_SAMP),
    Sig[Last](LAST),
    Sig[First](FIRST)
  )

  /** Mapping Spark window expression to Substrait function name */
  private val WINDOW_SIGS: Seq[Sig] = Seq(
    Sig[Rank](RANK),
    Sig[DenseRank](DENSE_RANK),
    Sig[RowNumber](ROW_NUMBER),
    Sig[CumeDist](CUME_DIST),
    Sig[PercentRank](PERCENT_RANK),
    Sig[NTile](NTILE),
    Sig[Lead](LEAD),
    Sig[Lag](LAG)
  )

  lazy val expressionsMap: Map[Class[_], String] = {
    (SCALAR_SIGS ++ AGGREGATE_SIGS ++ WINDOW_SIGS ++
      BackendsApiManager.getSparkPlanExecApiInstance.extraExpressionMappings)
      .map(s => (s.expClass, s.name))
      .toMap[Class[_], String]
  }

}
