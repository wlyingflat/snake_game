/**
 * shm_manager.h - 共享内存管理器接口
 *
 * 功能：声明共享内存操作函数。
 */

#ifndef SHM_MANAGER_H
#define SHM_MANAGER_H

#include "common.h"

// 共享内存管理
typedef struct {
  int shm_id;
  SharedData *data;
} SharedMemory;

// 初始化/销毁
SharedMemory *shm_create();
SharedMemory *shm_attach();
void shm_detach(SharedMemory *shm);
void shm_destroy(SharedMemory *shm);

// 锁操作
void shm_lock(SharedMemory *shm);
void shm_unlock(SharedMemory *shm);

// 房间操作
RoomInfo *shm_get_room(SharedMemory *shm, int room_id);
void shm_update_room(SharedMemory *shm, int room_id, RoomInfo *info);

// 用户操作
User *shm_find_user(SharedMemory *shm, const char *username);
int shm_add_user(SharedMemory *shm, User *user);
void shm_update_user(SharedMemory *shm, const char *username, User *user);

#endif // SHM_MANAGER_H
