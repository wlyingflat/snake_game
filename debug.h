/**
 * debug.h - 调试日志宏
 *
 * 功能：提供不同级别的日志输出，支持时间戳。
 */

#ifndef DEBUG_H
#define DEBUG_H

#include "config.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define LOG(level, fmt, ...)                                                   \
  do {                                                                         \
    struct timespec ts;                                                        \
    clock_gettime(CLOCK_REALTIME, &ts);                                        \
    fprintf(stderr, "[%s][%ld.%03ld] " fmt "\n", level, ts.tv_sec,             \
            ts.tv_nsec / 1000000, ##__VA_ARGS__);                              \
  } while (0)

#ifdef DEBUG
#define DBG(fmt, ...) LOG("DEBUG", fmt, ##__VA_ARGS__)
#define INFO(fmt, ...) LOG("INFO", fmt, ##__VA_ARGS__)
#define WARN(fmt, ...) LOG("WARN", fmt, ##__VA_ARGS__)
#define ERROR(fmt, ...) LOG("ERROR", fmt, ##__VA_ARGS__)

#else
#define DBG(fmt, ...)
#define INFO(fmt, ...)
#define WARN(fmt, ...)
#define ERROR(fmt, ...)
#endif

#define FATAL(fmt, ...)                                                        \
  do {                                                                         \
    LOG("FATAL", fmt, ##__VA_ARGS__);                                          \
    exit(EXIT_FAILURE);                                                        \
  } while (0)

#endif // DEBUG_H
