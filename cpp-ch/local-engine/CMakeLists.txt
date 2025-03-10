
if (COMPILER_CLANG)
    set (CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} --ld-path=${LLD_WRAPPER}")
endif ()

option(ENABLE_LOCAL_FORMATS "Use ORC/Parquet input formats defined in utils/local-engine/, notice that it is not stable and has some known issues. We suggest setting it to OFF in production environment." OFF)
if (ENABLE_LOCAL_FORMATS)
    add_definitions(-DENABLE_LOCAL_FORMATS=1)
else()
    add_definitions(-DENABLE_LOCAL_FORMATS=0)
endif()

set(THRIFT_INCLUDE_DIR "${ClickHouse_SOURCE_DIR}/contrib/thrift/lib/cpp/src")

# Find java/jni
include(FindJava)
include(UseJava)
include(FindJNI)

#set(JNI_NATIVE_SOURCES local_engine_jni.cpp)
set(LOCALENGINE_SHARED_LIB ch)
set (ENABLE_CURL_BUILD OFF)

add_subdirectory(proto)

add_headers_and_sources(builder Builder)
add_headers_and_sources(parser Parser)
add_headers_and_sources(storages Storages)
add_headers_and_sources(storages Storages/Output)
add_headers_and_sources(common Common)
add_headers_and_sources(external External)
add_headers_and_sources(shuffle Shuffle)
add_headers_and_sources(operator Operator)
add_headers_and_sources(jni jni)
add_headers_and_sources(aggregate_functions AggregateFunctions)

include_directories(
        ${JNI_INCLUDE_DIRS}
        ${CMAKE_CURRENT_BINARY_DIR}/proto
        ${THRIFT_INCLUDE_DIR}
        ${CMAKE_BINARY_DIR}/contrib/thrift-cmake
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${ClickHouse_SOURCE_DIR}/src
        ${ClickHouse_SOURCE_DIR}/base
        ${ClickHouse_SOURCE_DIR}/contrib/orc/c++/include
        ${CMAKE_BINARY_DIR}/contrib/orc/c++/include
        ${ClickHouse_SOURCE_DIR}/contrib/azure/sdk/storage/azure-storage-blobs/inc
        ${ClickHouse_SOURCE_DIR}/contrib/azure/sdk/core/azure-core/inc
        ${ClickHouse_SOURCE_DIR}/contrib/azure/sdk/storage/azure-storage-common/inc
)

if (ENABLE_LOCAL_FORMATS)
    add_subdirectory(Storages/ch_parquet)
endif()

add_subdirectory(Storages/SubstraitSource)
add_subdirectory(Functions)

add_library(gluten_clickhouse_backend_libs
        ${builder_sources}
        ${parser_sources}
        ${storages_sources}
        ${common_sources}
        ${external_sources}
        ${shuffle_sources}
        ${operator_sources}
        ${aggregate_functions_sources})

target_link_libraries(gluten_clickhouse_backend_libs PUBLIC
        substait_source                         # compile options from substait_source  
        clickhouse_aggregate_functions
        clickhouse_functions
        gluten_spark_functions
        ch_contrib::xxHash
)

# Add udf sources files in sub directories to functions_sources
option(ENABLE_LOCAL_UDFS "Build udfs in 'local-engine/Parser/*_udf' subdirectories" ON)
if (ENABLE_LOCAL_UDFS)
    file(GLOB children CONFIGURE_DEPENDS RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} Parser/*_udf)
    foreach(child ${children})
        add_headers_and_sources(local_udfs ${child})
    endforeach()
endif ()

# Notice: soures files under Parser/*_udf subdirectories must be built into target ${LOCALENGINE_SHARED_LIB} directly
#         to make sure all function parsers are registered successly.
add_library(${LOCALENGINE_SHARED_LIB} SHARED
        ${jni_sources}
        local_engine_jni.cpp
        ${local_udfs_sources}
        $<TARGET_OBJECTS:clickhouse_malloc>) # why add clickhouse_malloc? check clickhouse PR-8046

target_compile_options(${LOCALENGINE_SHARED_LIB} PUBLIC -fPIC
        -Wno-shorten-64-to-32)

target_link_libraries(${LOCALENGINE_SHARED_LIB}
PUBLIC
        clickhouse_new_delete
        clickhouse_common_config
        clickhouse_common_io
        clickhouse_parsers
        clickhouse_storages_system
        loggers
        gluten_clickhouse_backend_libs
        ch_contrib::protobuf
PRIVATE
        substrait
)

if (ENABLE_LOCAL_FORMATS)
    target_link_libraries(${LOCALENGINE_SHARED_LIB} PUBLIC ch_parquet)
endif ()

if (ENABLE_TESTS)
    add_subdirectory(tests)
else ()
    if (ENABLE_JEMALLOC)
        target_link_options(${LOCALENGINE_SHARED_LIB} PRIVATE
            -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/libch.map -Wl,-Bsymbolic-functions)
    else()
        target_link_options(${LOCALENGINE_SHARED_LIB} PRIVATE
            -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/libch-hide-jemalloc.map)
    endif()
endif ()

