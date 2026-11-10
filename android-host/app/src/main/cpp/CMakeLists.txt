cmake_minimum_required(VERSION 3.22)
project(AndroidRemoteOpenVPN LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# This is the actual upstream OpenVPN3 Core, downloaded by native/setup-upstream.sh.
set(OPENVPN3_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../../native/openvpn3")
if(NOT EXISTS "${OPENVPN3_SRC}/client/ovpncli.cpp")
  message(FATAL_ERROR "Brak OpenVPN 3 Core. Uruchom ../../native/setup-upstream.sh przed buildem APK")
endif()

# Dependencies built by native/setup-deps.sh for target Android ABI.
if(NOT DEFINED ENV{VCPKG_ROOT})
  message(FATAL_ERROR "Ustaw VCPKG_ROOT i zainstaluj zaleznosci Android (native/setup-deps.sh)")
endif()
set(VCPKG_LIB "$ENV{VCPKG_ROOT}/installed/${ANDROID_ABI}-android")
# vcpkg target triplet for arm64-v8a is arm64-android.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(VCPKG_LIB "$ENV{VCPKG_ROOT}/installed/arm64-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(VCPKG_LIB "$ENV{VCPKG_ROOT}/installed/x64-android")
endif()
list(PREPEND CMAKE_PREFIX_PATH "${VCPKG_LIB}")
find_package(asio CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)
find_package(lz4 CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)

add_library(remoteopenvpn SHARED
  native_openvpn.cpp
  "${OPENVPN3_SRC}/client/ovpncli.cpp"
  "${OPENVPN3_SRC}/openvpn/crypto/data_epoch.cpp")
target_include_directories(remoteopenvpn PRIVATE "${OPENVPN3_SRC}" "${OPENVPN3_SRC}/client")
target_compile_definitions(remoteopenvpn PRIVATE
  OPENVPN_PLATFORM_ANDROID ASIO_STANDALONE USE_ASIO USE_OPENSSL HAVE_LZ4)
target_link_libraries(remoteopenvpn PRIVATE asio::asio fmt::fmt lz4::lz4 OpenSSL::SSL OpenSSL::Crypto log)
