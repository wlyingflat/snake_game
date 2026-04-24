/**
 * common.h - 公共类型定义和常量
 *
 * 功能：包含所有模块共享的结构体、枚举、协议命令等。
 */

#ifndef COMMON_H
#define COMMON_H

#include "config.h"
#include <arpa/inet.h>
#include <openssl/sha.h>
#include <pthread.h>
#include <stdint.h>

// ================= 基本类型定义 =================
typedef enum { DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT } Direction;

typedef struct {
  int x;
  int y;
} Position;

// ================= 用户相关结构 =================
typedef struct {
  char name[USERNAME_LEN];
  unsigned char password_hash[SHA256_DIGEST_LENGTH];
  uint32_t salt; // 随机盐（4字节）
  int online;
  time_t last_active;
} User;

// ================= 房间相关结构 =================
typedef enum { ROOM_CLOSED = 0, ROOM_OPEN = 1, ROOM_FULL = 2 } RoomStatus;

typedef struct {
  int room_id;
  pid_t process_id;
  int port;
  int player_count;
  int max_players;
  RoomStatus status;
  time_t created_at;
  time_t last_activity;
} RoomInfo;

// ================= 玩家相关结构 =================
typedef struct {
  int is_used;
  int socket_fd;
  int player_id;
  Position body[MAX_SNAKE_LENGTH];
  int length;
  Direction direction;
  int score;
  char name[USERNAME_LEN];
  int is_dead;
  time_t join_time;
} Player;

// ================= 游戏世界结构 =================
typedef struct {
  char map[MAP_HEIGHT][MAP_WIDTH];
  Position food;
  Position obstacles[OBSTACLE_COUNT];
  Player players[MAX_PLAYERS_PER_ROOM];
  pthread_rwlock_t lock;
  int initial_delay_done;
  int total_players;
  int active_players;
  volatile int should_shutdown;
} GameWorld;

// ================= 共享内存结构 =================
typedef struct {
  RoomInfo rooms[MAX_ROOMS];
  User users[MAX_USERS];
  int user_count;
  pthread_mutex_t lock;
  time_t last_updated;
  int initialized; // 新增：共享内存是否已初始化
} SharedData;

// ================= 网络相关结构 =================
typedef struct {
  int socket_fd;
  char buffer[BUFFER_SIZE];
  struct sockaddr_in address;
} ClientRequest;

// ================= 游戏状态传输结构 =================
typedef struct {
  int room_id;
  Position food;

  struct {
    int x;
    int y;
  } obstacles[OBSTACLE_COUNT];
  int obstacle_count;

  struct {
    char name[USERNAME_LEN];
    Position head;
    struct {
      int x;
      int y;
    } body[MAX_SNAKE_LENGTH];
    int length;
    Direction direction;
    int score;
    int is_dead;
    int is_you;
  } players[MAX_PLAYERS_PER_ROOM];

  int player_count;
  int active_players;
  int total_players;
} GameStateData;

// ================= 协议消息定义 =================
// 客户端到服务器
#define CMD_REGISTER "REG"
#define CMD_LOGIN "LOGIN"
#define CMD_CREATE "CREATE"
#define CMD_JOIN "JOIN"
#define CMD_LOGOUT "LOGOUT"
#define CMD_ROOM_LIST "ROOM_LIST"

// 服务器到客户端
#define RESP_OK "OK"
#define RESP_ERROR "ERROR"
#define RESP_REDIRECT "REDIRECT"
#define RESP_ALREADY_IN "ALREADY IN ROOM"
#define RESP_ROOM_FULL "ROOM FULL"
#define RESP_INVALID "INVALID"
#define RESP_YOU_DIED "YOU DIED"
#define RESP_WELCOME "WELCOME"
#define RESP_GAME_STATE "GAME_STATE"

#endif // COMMON_H
