/**
 * config.h - 系统全局配置
 *
 * 功能：定义所有模块使用的常量。
 */

#ifndef CONFIG_H
#define CONFIG_H

// ================= 系统配置 =================
#define DEBUG 1

// 网络配置
#define MAX_EVENTS 64
#define MAX_CLIENTS 1024
#define BACKLOG 128
#define BUFFER_SIZE 8192 // 增大缓冲区
#define RECV_TIMEOUT 5   // 接收超时(秒)

// 共享内存配置
#define SHM_KEY 0x1234

// ================= 主服务器配置 =================
#define MAX_USERS 1024
#define USERNAME_LEN 32
#define PASSWORD_HASH_LEN 64
#define THREAD_POOL_SIZE 4
#define THREAD_QUEUE_SIZE 1024

// ================= 房间配置 =================
#define MAX_ROOMS 8
#define MAX_PLAYERS_PER_ROOM 8
#define BASE_ROOM_PORT 20000
#define ROOM_IDLE_TIMEOUT 30
#define ROOM_INIT_DELAY_TICKS 5

// ================= 游戏配置 =================
#define MAP_WIDTH 40
#define MAP_HEIGHT 20
#define TICK_INTERVAL_MS 200
#define INIT_SNAKE_LENGTH 1
#define MAX_SNAKE_LENGTH 63
#define OBSTACLE_COUNT 15
#define MAX_SPAWN_ATTEMPTS 100

// ================= 客户端配置 =================
#define MAX_RETRY_ATTEMPTS 5
#define RETRY_DELAY_MS 200
#define INPUT_TIMEOUT_US 10000

// ================= 网关配置 =================
#define GATEWAY_DEFAULT_PORT 19000 // 默认网关端口
#define HEARTBEAT_INTERVAL 30      // 心跳间隔（秒）
#define HEARTBEAT_TIMEOUT 60       // 心跳超时（秒）

// ================= 通知通道 =================
#define GATEWAY_SOCK_PATH "/tmp/gateway.sock" // Unix域套接字路径

#endif // CONFIG_H
