/**
 * game_world.h - 游戏世界管理接口
 *
 * 功能：声明游戏世界相关的函数和数据结构。
 */

#ifndef GAME_WORLD_H
#define GAME_WORLD_H

#include "common.h"
#include "shm_manager.h"

// 游戏世界管理
typedef struct {
  int room_id;
  GameWorld world;
  SharedMemory *shm;
  time_t last_tick_time;
} GameManager;

// 初始化/销毁
GameManager *game_manager_create(int room_id, SharedMemory *shm);
void game_manager_destroy(GameManager *manager);

// 世界操作
void game_init_world(GameManager *manager);
void game_update_world(GameManager *manager);

// 玩家管理
int game_add_player(GameManager *manager, int socket_fd, const char *username);
int game_remove_player(GameManager *manager, int socket_fd);
int game_update_player_direction(GameManager *manager, int socket_fd,
                                 Direction dir);

// 碰撞检测
int game_check_collision(GameManager *manager, Position pos);
Position game_find_safe_position(GameManager *manager);

// 房间关闭检查
int game_should_shutdown(GameManager *manager);

// 获取游戏状态数据
int game_get_state_data(GameManager *manager, GameStateData *state_data,
                        int player_socket_fd);

#endif // GAME_WORLD_H
