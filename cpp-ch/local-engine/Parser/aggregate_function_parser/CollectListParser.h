#pragma once
#include "CommonAggregateFunctionParser.h"
#include <DataTypes/DataTypeNullable.h>
#include <Interpreters/ActionsDAG.h>

namespace DB
{
namespace ErrorCodes
{
    extern const int NOT_IMPLEMENTED;
}
}

namespace local_engine
{
// groupArray is used to implement collect_list in spark. But there is a difference between them.
// If all elements are null, collect_list will return [], but groupArray will return null. And clickhosue
// has backward compatibility issue, we cannot modify the inner implementation of groupArray
class CollectFunctionParser : public BaseAggregateFunctionParser
{
public:
    explicit CollectFunctionParser(SerializedPlanParser * plan_parser_) : BaseAggregateFunctionParser(plan_parser_) { }
    ~CollectFunctionParser() override = default;
    virtual String getName() const override
    {
        throw DB::Exception(DB::ErrorCodes::NOT_IMPLEMENTED, "Not implement");
    }

    virtual String getCHFunctionName(const CommonFunctionInfo &) const override
    {
        throw DB::Exception(DB::ErrorCodes::NOT_IMPLEMENTED, "Not implement");
    }

    virtual String getCHFunctionName(const DB::DataTypes &) const override
    {
        throw DB::Exception(DB::ErrorCodes::NOT_IMPLEMENTED, "Not implement");
    }
    const DB::ActionsDAG::Node * convertNodeTypeIfNeeded(
        const CommonFunctionInfo &, const DB::ActionsDAG::Node * func_node, DB::ActionsDAGPtr & actions_dag) const override
    {
        const DB::ActionsDAG::Node * ret_node = func_node;
        if (func_node->result_type->isNullable())
        {
            DB::ActionsDAG::NodeRawConstPtrs args = {func_node};
            auto nested_type = typeid_cast<const DB::DataTypeNullable *>(func_node->result_type.get())->getNestedType();
            DB::Field empty_field = nested_type->getDefault();
            const auto * default_value_node = &actions_dag->addColumn(
                ColumnWithTypeAndName(nested_type->createColumnConst(1, empty_field), nested_type, getUniqueName("[]")));
            args.push_back(default_value_node);
            const auto * if_null_node = toFunctionNode(actions_dag, "ifNull", func_node->result_name, args);
            actions_dag->addOrReplaceInOutputs(*if_null_node);
            ret_node = if_null_node;
        }
        return ret_node;
    }
};

class CollectListParser : public CollectFunctionParser
{
public:
    explicit CollectListParser(SerializedPlanParser * plan_parser_) : CollectFunctionParser(plan_parser_) { }
    ~CollectListParser() override = default;
    static constexpr auto name = "collect_list";
    String getName() const override { return name; }
    String getCHFunctionName(const CommonFunctionInfo &) const override { return "groupArray"; }
    String getCHFunctionName(const DB::DataTypes &) const override { return "groupArray"; }
};

class CollectSetParser : public CollectFunctionParser
{
public:
    explicit CollectSetParser(SerializedPlanParser * plan_parser_) : CollectFunctionParser(plan_parser_) { }
    ~CollectSetParser() override = default;
    static constexpr auto name = "collect_set";
    String getName() const override { return name; }
    String getCHFunctionName(const CommonFunctionInfo &) const override { return "groupUniqArray"; }
    String getCHFunctionName(const DB::DataTypes &) const override { return "groupUniqArray"; }
};
}
