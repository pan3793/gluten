#include <Functions/FunctionFactory.h>
#include <Common/CHUtil.h>
#include <DataTypes/IDataType.h>
#include <Core/Field.h>

#include "FunctionParser.h"

namespace DB
{

namespace ErrorCodes
{
    extern const int UNKNOWN_FUNCTION;
}
}

namespace local_engine
{
using namespace DB;

String FunctionParser::getCHFunctionName(const substrait::Expression_ScalarFunction & substrait_func) const
{
    auto func_signature = plan_parser->function_mapping.at(std::to_string(substrait_func.function_reference()));
    auto pos = func_signature.find(':');
    auto func_name = func_signature.substr(0, pos);

    auto it = SCALAR_FUNCTIONS.find(func_name);
    if (it == SCALAR_FUNCTIONS.end())
        throw Exception(ErrorCodes::UNKNOWN_FUNCTION, "Unsupported substrait function: {}", func_name);
    return it->second;
}

ActionsDAG::NodeRawConstPtrs FunctionParser::parseFunctionArguments(
    const substrait::Expression_ScalarFunction & substrait_func,
    const String & ch_func_name,
    ActionsDAGPtr & actions_dag,
    std::vector<String> & required_columns) const
{
    auto add_column = [&](const DataTypePtr & type, const Field & field) -> auto
    {
        return &actions_dag->addColumn(
            ColumnWithTypeAndName(type->createColumnConst(1, field), type, plan_parser->getUniqueName(toString(field))));
    };

    ActionsDAG::NodeRawConstPtrs parsed_args;
    const auto & args = substrait_func.arguments();
    parsed_args.reserve(args.size());
    for (const auto & arg : args)
        plan_parser->parseFunctionArgument(actions_dag, parsed_args, required_columns, ch_func_name, arg);
    return parsed_args;
}

const ActionsDAG::Node * FunctionParser::parse(
    const substrait::Expression_ScalarFunction & substrait_func,
    ActionsDAGPtr & actions_dag,
    std::vector<String> & required_columns) const
{
    auto ch_func_name = getCHFunctionName(substrait_func);
    auto parsed_args = parseFunctionArguments(substrait_func, ch_func_name, actions_dag, required_columns);
    const auto * func_node = toFunctionNode(actions_dag, ch_func_name, parsed_args);
    return convertNodeTypeIfNeeded(substrait_func, func_node, actions_dag);
}

const ActionsDAG::Node * FunctionParser::convertNodeTypeIfNeeded(
    const substrait::Expression_ScalarFunction & substrait_func, const ActionsDAG::Node * func_node, ActionsDAGPtr & actions_dag) const
{
    const auto & output_type = substrait_func.output_type();
    if (!isTypeMatched(output_type, func_node->result_type))
        return ActionsDAGUtil::convertNodeType(
            actions_dag, func_node, SerializedPlanParser::parseType(output_type)->getName(), func_node->result_name);
    else
        return func_node;
}

void FunctionParserFactory::registerFunctionParser(const String & name, Value value)
{
    if (!parsers.emplace(name, value).second)
        throw Exception(ErrorCodes::LOGICAL_ERROR, "FunctionParserFactory: function parser name '{}' is not unique", name);
}


FunctionParserPtr FunctionParserFactory::get(const String & name, SerializedPlanParser * plan_parser)
{
    auto res = tryGet(name, plan_parser);
    if (!res)
        throw Exception(ErrorCodes::UNKNOWN_FUNCTION, "Unknown function parser {}", name);

    return res;
}

FunctionParserPtr FunctionParserFactory::tryGet(const String & name, SerializedPlanParser * plan_parser)
{
    auto it = parsers.find(name);
    if (it != parsers.end())
    {
        auto creator = it->second;
        return creator(plan_parser);
    }
    else
        return {};
}

FunctionParserFactory & FunctionParserFactory::instance()
{
    static FunctionParserFactory factory;
    return factory;
}

}
