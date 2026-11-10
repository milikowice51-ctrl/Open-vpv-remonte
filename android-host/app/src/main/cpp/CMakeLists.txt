
cmake_minimum_required(VERSION 3.22)
project(AndroidRemoteOpenVPN LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# OpenVPN 3 Core
# Pobierany przez native/setup-upstream.sh
set(OPENVPN3_SRC
    "${CMAKE_CURRENT_LIST_DIR}/../../../../../native/openvpn3"
)

if(NOT EXISTS "${OPENVPN3_SRC}/client/ovpncli.cpp")
    message(FATAL_ERROR
        "Brak OpenVPN 3 Core. Uruchom "
        "native/setup-upstream.sh przed buildem APK"
    )
endif()

# Zaleznosci Android przez vcpkg
if(NOT DEFINED ENV{VCPKG_ROOT})
    message(FATAL_ERROR
        "Ustaw VCPKG_ROOT i zainstaluj "
        "zaleznosci Android przez native/setup-deps.sh"
    )
endif()

set(VCPKG_LIB
    "$ENV{VCPKG_ROOT}/installed/${ANDROID_ABI}-android"
)

# Mapowanie Android ABI na triplet vcpkg
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_LIB
        "$ENV{VCPKG_ROOT}/installed/arm64-android"
    )
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_LIB
        "$ENV{VCPKG_ROOT}/installed/x64-android"
    )
endif()

# Sprawdzenie biblioteki Asio
if(NOT EXISTS "${VCPKG_LIB}/share/asio/asio-config.cmake")
    message(FATAL_ERROR
        "Asio for ${ANDROID_ABI} not found in ${VCPKG_LIB}. "
        "Run native/setup-deps.sh before building the APK."
    )
endif()

# Poprawka v0.5.2:
# Dodanie zaleznosci vcpkg do wyszukiwania CMake
# Android NDK ogranicza wyszukiwanie pakietow
# do katalogow systemu docelowego.

list(APPEND CMAKE_FIND_ROOT_PATH "${VCPKG_LIB}")
list(PREPEND CMAKE_PREFIX_PATH "${VCPKG_LIB}")

message(STATUS
    "Using Android vcpkg dependencies in ${VCPKG_LIB}"
)

# Biblioteki wymagane przez OpenVPN 3 Core
find_package(asio CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)
find_package(lz4 CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)

# Natywna biblioteka OpenVPN dla Androida
add_library(remoteopenvpn SHARED
    native_openvpn.cpp
    "${OPENVPN3_SRC}/client/ovpncli.cpp"
    "${OPENVPN3_SRC}/openvpn/crypto/data_epoch.cpp"
)

# Sciezki naglowkow
target_include_directories(remoteopenvpn PRIVATE
    "${OPENVPN3_SRC}"
    "${OPENVPN3_SRC}/client"
)

# Konfiguracja kompilatora C++
target_compile_definitions(remoteopenvpn PRIVATE
    OPENVPN_PLATFORM_ANDROID
    ASIO_STANDALONE
    USE_ASIO
    USE_OPENSSL
    HAVE_LZ4
)

# Linkowanie bibliotek
target_link_libraries(remoteopenvpn PRIVATE
    asio::asio
    fmt::fmt
    lz4::lz4
    OpenSSL::SSL
    OpenSSL::Crypto
    log
)
