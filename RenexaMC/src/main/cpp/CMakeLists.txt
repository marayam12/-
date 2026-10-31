cmake_minimum_required(VERSION 3.22.1)
project("renexamc")

add_library(renexamc SHARED
    crashguard.c
)

find_library(log-lib log)

target_link_libraries(renexamc
    ${log-lib}
)

# libsecurity.so - app integrity / anti-tamper check (see security.c).
add_library(security SHARED
    security.c
)

target_link_libraries(security
    ${log-lib}
)
