# IVO Mirror · receba a tela do iPhone no IVO LIVE — native part: UxPlay's AirPlay server (GPL-3.0), libplist
# (LGPL-2.1), OpenSSL 3 libcrypto (Apache-2.0, built for Android, see openssl/), and the bridge to
# the app (android/).
cmake_minimum_required(VERSION 3.22)
project(ivoairplay C)

set(UX ${CMAKE_CURRENT_SOURCE_DIR}/uxplay/lib)
set(PL ${CMAKE_CURRENT_SOURCE_DIR}/libplist)

file(GLOB UX_SRC ${UX}/*.c)
file(GLOB PF_SRC ${UX}/playfair/*.c)
file(GLOB LH_SRC ${UX}/llhttp/*.c)
file(GLOB PL_SRC ${PL}/src/*.c ${PL}/libcnary/*.c)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/openssl/lib/${ANDROID_ABI}/libcrypto.a)

add_library(plist STATIC ${PL_SRC})
target_include_directories(plist PUBLIC ${PL}/include PRIVATE ${PL}/src ${PL}/libcnary/include)
target_compile_definitions(plist PRIVATE PACKAGE_VERSION="2.8.1" HAVE_STRNDUP HAVE_STRPTIME HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TIMEGM)
target_compile_options(plist PRIVATE -Wno-unused-parameter -Wno-sign-compare)

add_library(ivoairplay SHARED ${UX_SRC} ${PF_SRC} ${LH_SRC}
            android/dnssd_android.c android/bridge.c)
target_include_directories(ivoairplay PRIVATE ${UX} ${UX}/playfair ${UX}/llhttp
                           ${CMAKE_CURRENT_SOURCE_DIR}/openssl/include)
target_compile_definitions(ivoairplay PRIVATE STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS
                           TARGET_POSIX _LINUX _REENTRANT NOHOLD PLIST_210 PLIST_230
                           OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(ivoairplay PRIVATE -O2 -Wall -Wno-unused-variable -Wno-unused-function)
target_link_libraries(ivoairplay plist crypto log)
