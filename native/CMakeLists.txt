cmake_minimum_required(VERSION 3.22)
project(bc3miner C)

set(MINER "${CMAKE_CURRENT_LIST_DIR}/../donate1")
if(NOT DEFINED CURL_ROOT)
  message(FATAL_ERROR "CURL_ROOT must point to the static Android curl install")
endif()

add_executable(bc3miner
  "${MINER}/cpu-miner.c"
  "${MINER}/util.c"
  "${MINER}/api.c"
  "${MINER}/sysinfos.c"
  "${MINER}/algo-gate-api.c"
  "${MINER}/malloc-huge.c"
  "${MINER}/simd-utils/simd-constants.c"
  "${MINER}/algo/keccak/keccak-gate.c"
  "${MINER}/algo/keccak/sha3d.c"
  "${MINER}/algo/keccak/sha3d-4way.c"
  "${MINER}/algo/keccak/keccak-hash-4way.c"
  "${CMAKE_CURRENT_LIST_DIR}/sha256d-portable.c"
  "${MINER}/algo/sha/sph_sha2.c"
  "${MINER}/algo/sha/sha1.c"
  "${MINER}/algo/verthash/tiny_sha3/sha3.c"
  "${MINER}/compat/jansson/dump.c"
  "${MINER}/compat/jansson/error.c"
  "${MINER}/compat/jansson/hashtable.c"
  "${MINER}/compat/jansson/load.c"
  "${MINER}/compat/jansson/memory.c"
  "${MINER}/compat/jansson/pack_unpack.c"
  "${MINER}/compat/jansson/strbuffer.c"
  "${MINER}/compat/jansson/strconv.c"
  "${MINER}/compat/jansson/utf.c"
  "${MINER}/compat/jansson/value.c"
)

target_include_directories(bc3miner PRIVATE
  "${MINER}"
  "${MINER}/compat/jansson"
  "${CURL_ROOT}/include"
)
target_compile_definitions(bc3miner PRIVATE BC3_SHA3D_ONLY=1 CURL_STATICLIB=1 _GNU_SOURCE=1)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
  target_compile_definitions(bc3miner PRIVATE BC3_PORTABLE=1)
endif()
target_compile_options(bc3miner PRIVATE -O3 -flto=thin -fPIE -Wno-implicit-function-declaration)
target_link_options(bc3miner PRIVATE -flto=thin -fPIE -pie)
target_link_libraries(bc3miner PRIVATE "${CURL_ROOT}/lib/libcurl.a" log m dl)

set_target_properties(bc3miner PROPERTIES OUTPUT_NAME "libbc3miner.so" PREFIX "" SUFFIX "")
