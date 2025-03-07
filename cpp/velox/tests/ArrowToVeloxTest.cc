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

#include "jni/JniErrors.h"
#include "memory/VeloxMemoryPool.h"
#include "utils/TestUtils.h"
#include "velox/vector/arrow/Bridge.h"
#include "velox/vector/tests/utils/VectorTestBase.h"

#include <arrow/c/abi.h>
#include <arrow/c/bridge.h>
#include <arrow/c/helpers.h>
#include <arrow/record_batch.h>
#include <arrow/type_fwd.h>
#include <gtest/gtest.h>

using namespace facebook;
using namespace facebook::velox;
using namespace arrow;

namespace gluten {
class ArrowToVeloxTest : public ::testing::Test, public test::VectorTestBase {};

velox::VectorPtr recordBatch2RowVector(const RecordBatch& rb) {
  ArrowArray arrowArray;
  ArrowSchema arrowSchema;
  ASSERT_NOT_OK(ExportRecordBatch(rb, &arrowArray, &arrowSchema));
  return velox::importFromArrowAsOwner(arrowSchema, arrowArray, gluten::getDefaultVeloxLeafMemoryPool().get());
}

void checkBatchEqual(std::shared_ptr<RecordBatch> inputBatch, bool checkMetadata = true) {
  velox::VectorPtr vp = recordBatch2RowVector(*inputBatch);
  ArrowArray arrowArray;
  ArrowSchema arrowSchema;
  velox::exportToArrow(vp, arrowArray, getDefaultVeloxLeafMemoryPool().get());
  velox::exportToArrow(vp, arrowSchema);
  auto in = gluten::jniGetOrThrow(ImportRecordBatch(&arrowArray, &arrowSchema));
  ASSERT_TRUE(in->Equals(*inputBatch, checkMetadata)) << in->ToString() << inputBatch->ToString();
}

TEST_F(ArrowToVeloxTest, arrowToVelox) {
  std::vector<std::shared_ptr<Field>> fields = {
      field("f_int8_a", int8()),
      field("f_int8_b", int8()),
      field("f_int32", int32()),
      field("f_int64", int64()),
      field("f_double", float64()),
      field("f_bool", boolean()),
      field("f_string", utf8()),
      field("f_nullable_string", utf8()),
      field("f_binary", binary()),
      field("f_date", date32())};

  auto schema = arrow::schema(fields);
  std::shared_ptr<RecordBatch> inputBatch;
  const std::vector<std::string> inputData = {
      "[null, null]",
      "[1, -1]",
      "[100, null]",
      "[1, 1]",
      R"([0.142857, -0.142857])",
      "[true, false]",
      R"(["bob", "alicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealicealice"])",
      R"([null, null])",
      R"(["aa", "bb"])",
      R"([1, 31])"};

  makeInputBatch(inputData, schema, &inputBatch);

  checkBatchEqual(inputBatch);
}

TEST_F(ArrowToVeloxTest, unsupport) {
  auto f2 = {field("f2", timestamp(TimeUnit::MICRO))};
  auto schema = arrow::schema(f2);
  std::shared_ptr<RecordBatch> inputBatch;
  const std::vector<std::string> inputData = {R"(["1970-01-01","2000-02-29","3989-07-14","1900-02-28"])"};

  makeInputBatch(inputData, schema, &inputBatch);
  checkBatchEqual(inputBatch);
}

TEST_F(ArrowToVeloxTest, decimalA2V) {
  std::vector<std::shared_ptr<Field>> fields = {field("f_decimal128", decimal(10, 2))};

  auto schema = arrow::schema(fields);
  std::shared_ptr<RecordBatch> inputBatch;
  const std::vector<std::string> inputData = {R"(["-1.01", "2.95"])"};

  makeInputBatch(inputData, schema, &inputBatch);
  checkBatchEqual(inputBatch);
}

TEST_F(ArrowToVeloxTest, decimalV2A) {
  // only RowVector can convert to RecordBatch
  auto row = makeRowVector({
      makeShortDecimalFlatVector({1000265000, -35610000, 0}, DECIMAL(10, 3)),
  });

  std::vector<std::shared_ptr<Field>> fields = {field("c0", decimal(10, 3))};
  auto schema = arrow::schema(fields);
  std::shared_ptr<RecordBatch> inputBatch;
  const std::vector<std::string> inputData = {R"(["1000265.000", "-35610.000", "0.000"])"};
  makeInputBatch(inputData, schema, &inputBatch);

  ArrowArray arrowArray;
  ArrowSchema arrowSchema;
  velox::exportToArrow(row, arrowArray, getDefaultVeloxLeafMemoryPool().get());
  velox::exportToArrow(row, arrowSchema);

  auto in = gluten::jniGetOrThrow(ImportRecordBatch(&arrowArray, &arrowSchema));
  EXPECT_TRUE(in->Equals(*inputBatch));
  ArrowArrayRelease(&arrowArray);
}

TEST_F(ArrowToVeloxTest, timestampV2A) {
  // only RowVector can convert to RecordBatch, FlatVector cannot convert to RecordBatch
  std::vector<Timestamp> timeValues = {
      Timestamp{0, 0}, Timestamp{12, 0}, Timestamp{0, 17'123'456}, Timestamp{1, 17'123'456}, Timestamp{-1, 17'123'456}};
  auto row = makeRowVector({
      makeFlatVector<Timestamp>(timeValues),
  });
  ArrowArray arrowArray;
  ArrowSchema arrowSchema;
  velox::exportToArrow(row, arrowArray, getDefaultVeloxLeafMemoryPool().get());
  velox::exportToArrow(row, arrowSchema);
  ArrowArrayRelease(&arrowArray);
}

TEST_F(ArrowToVeloxTest, listmap) {
  auto fArrInt32 = field("f_int32", list(list(int32())));
  auto fArrListMap = field("f_list_map", list(map(utf8(), utf8())));

  auto rbSchema = schema({fArrInt32, fArrListMap});

  const std::vector<std::string> inputDataArr = {
      R"([[[1, 2, 3]], [[9, 8], [null]], [[3, 1], [0]], [[1, 9, null]]])",
      R"([[[["key1", "val_aa1"]]], [[["key1", "val_bb1"]], [["key2", "val_bb2"]]], [[["key1", "val_cc1"]]], [[["key1", "val_dd1"]]]])"};

  std::shared_ptr<RecordBatch> inputBatch;
  makeInputBatch(inputDataArr, rbSchema, &inputBatch);
  checkBatchEqual(inputBatch);
}

TEST_F(ArrowToVeloxTest, struct) {
  auto fArrInt32 = field("f_int32", list(list(int32())));
  auto fArrListStruct = field("f_list_struct", list(struct_({field("a", int32()), field("b", utf8())})));

  auto rbSchema = schema({fArrInt32, fArrListStruct});

  const std::vector<std::string> inputDataArr = {
      R"([[[1, 2, 3]], [[9, 8], [null]], [[3, 1], [0]], [[1, 9, null]]])",
      R"([[{"a": 4, "b": null}], [{"a": 42, "b": null}, {"a": null, "b": "foo2"}], [{"a": 43, "b": "foo3"}], [{"a": 44, "b": "foo4"}]])"};

  std::shared_ptr<RecordBatch> inputBatch;
  makeInputBatch(inputDataArr, rbSchema, &inputBatch);
  checkBatchEqual(inputBatch);
}
} // namespace gluten
