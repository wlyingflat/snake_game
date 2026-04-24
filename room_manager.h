/**
 * room_manager.h - 房间管理器接口
 *
 * 功能：声明房间管理相关的函数。
 */

#ifndef ROOM_MANAGER_H
#define ROOM_MANAGER_H

#include "common.h"
#include "shm_manager.h"

// 房间管理
typedef struct {
  SharedMemory *shm;
  int base_port;
} RoomManager;

// 初始化/销毁
RoomManager *room_manager_create(SharedMemory *shm, int base_port);
void room_manager_destroy(RoomManager *manager);

// 房间操作
int room_create(RoomManager *manager, int room_id, const char *creator);
int room_join(RoomManager *manager, int room_id, const char *username);
int room_leave(RoomManager *manager, int room_id, const char *username);
int room_close(RoomManager *manager, int room_id);

// 房间信息
char *room_list(RoomManager *manager);
RoomStatus room_get_status(RoomManager *manager, int room_id);
int room_get_player_count(RoomManager *manager, int room_id);

// 房间进程管理
pid_t room_start_process(int room_id, int port);
void room_stop_process(int room_id);

#endif // ROOM_MANAGER_H
