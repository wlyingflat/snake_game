/**
 * shm_manager.c - 共享内存管理器（优化精简版）
 *
 * 功能：创建、附加、分离、销毁共享内存，并提供锁操作和房间/用户访问接口。
 */

#include "shm_manager.h"
#include "debug.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <time.h>

/* ---------------------------- 静态函数声明 ---------------------------- */
static key_t get_shm_key(void);
static void shm_init_data(SharedData *data);

/* ---------------------------- 函数实现 ---------------------------- */

/**
 * 获取共享内存键值
 */
static key_t get_shm_key(void) {
  key_t key = ftok("/tmp", 'S');
  if (key == -1) {
    WARN("ftok failed, using default key 0x534E414B");
    return 0x534E414B; // "SNAK"
  }
  return key;
}

/**
 * 初始化共享内存数据结构（必须在锁内调用）
 */
static void shm_init_data(SharedData *data) {
  DBG("Initializing shared memory data...");
  memset(data, 0, sizeof(SharedData));

  pthread_mutexattr_t attr;
  pthread_mutexattr_init(&attr);
  pthread_mutexattr_setpshared(&attr, PTHREAD_PROCESS_SHARED);
  pthread_mutex_init(&data->lock, &attr);
  pthread_mutexattr_destroy(&attr);

  for (int i = 0; i < MAX_ROOMS; i++) {
    data->rooms[i].room_id = i;
    data->rooms[i].port = BASE_ROOM_PORT + i;
    data->rooms[i].max_players = MAX_PLAYERS_PER_ROOM;
    data->rooms[i].status = ROOM_CLOSED;
    data->rooms[i].created_at = time(NULL);
    data->rooms[i].last_activity = time(NULL);
  }

  data->user_count = 0;
  data->last_updated = time(NULL);
  data->initialized = 1;
  INFO("Shared memory initialized successfully");
}

/**
 * 创建共享内存（自动处理大小不匹配）
 */
SharedMemory *shm_create() {
  SharedMemory *shm = malloc(sizeof(SharedMemory));
  if (!shm) {
    ERROR("Failed to allocate shared memory structure");
    return NULL;
  }

  key_t key = get_shm_key();
  INFO("Using shared memory key: 0x%x", key);

  // 尝试创建新的共享内存段
  shm->shm_id = shmget(key, sizeof(SharedData), IPC_CREAT | IPC_EXCL | 0666);
  if (shm->shm_id < 0) {
    if (errno != EEXIST) {
      ERROR("Failed to create shared memory: %s", strerror(errno));
      free(shm);
      return NULL;
    }

    // 已存在，获取现有段并检查大小
    shm->shm_id = shmget(key, 0, 0666);
    if (shm->shm_id < 0) {
      ERROR("Failed to access existing shared memory: %s", strerror(errno));
      free(shm);
      return NULL;
    }

    struct shmid_ds buf;
    if (shmctl(shm->shm_id, IPC_STAT, &buf) < 0) {
      ERROR("Failed to stat shared memory: %s", strerror(errno));
      free(shm);
      return NULL;
    }

    if ((size_t)buf.shm_segsz < sizeof(SharedData)) {
      WARN("Existing segment too small (%zu < %zu), removing it",
           (size_t)buf.shm_segsz, sizeof(SharedData));
      if (shmctl(shm->shm_id, IPC_RMID, NULL) < 0) {
        ERROR("Failed to remove old shared memory: %s", strerror(errno));
        free(shm);
        return NULL;
      }

      // 重新创建
      shm->shm_id =
          shmget(key, sizeof(SharedData), IPC_CREAT | IPC_EXCL | 0666);
      if (shm->shm_id < 0) {
        ERROR("Failed to re-create shared memory: %s", strerror(errno));
        free(shm);
        return NULL;
      }
      INFO("Created new shared memory segment (id: %d)", shm->shm_id);
    } else {
      DBG("Using existing shared memory segment (id: %d)", shm->shm_id);
    }
  } else {
    INFO("Created new shared memory segment (id: %d)", shm->shm_id);
  }

  shm->data = (SharedData *)shmat(shm->shm_id, NULL, 0);
  if (shm->data == (void *)-1) {
    ERROR("Failed to attach to shared memory: %s", strerror(errno));
    free(shm);
    return NULL;
  }

  shm_lock(shm);
  if (!shm->data->initialized)
    shm_init_data(shm->data);
  shm_unlock(shm);

  INFO("Shared memory ready (key: 0x%x, id: %d, size: %zu bytes)", key,
       shm->shm_id, sizeof(SharedData));
  return shm;
}

/**
 * 附加到现有共享内存
 */
SharedMemory *shm_attach() {
  SharedMemory *shm = malloc(sizeof(SharedMemory));
  if (!shm) {
    ERROR("Failed to allocate shared memory structure");
    return NULL;
  }

  key_t key = get_shm_key();
  shm->shm_id = shmget(key, 0, 0666);
  if (shm->shm_id < 0) {
    ERROR("Failed to get shared memory: %s (key: 0x%x)", strerror(errno), key);
    free(shm);
    return NULL;
  }

  shm->data = (SharedData *)shmat(shm->shm_id, NULL, 0);
  if (shm->data == (void *)-1) {
    ERROR("Failed to attach to shared memory: %s", strerror(errno));
    free(shm);
    return NULL;
  }

  INFO("Attached to shared memory (key: 0x%x, id: %d)", key, shm->shm_id);
  return shm;
}

/**
 * 分离共享内存
 */
void shm_detach(SharedMemory *shm) {
  if (!shm)
    return;
  if (shm->data && shm->data != (void *)-1) {
    if (shmdt(shm->data) < 0)
      ERROR("Failed to detach from shared memory: %s", strerror(errno));
    else
      DBG("Detached from shared memory");
  }
  free(shm);
}

/**
 * 销毁共享内存（仅应由主服务器调用）
 */
void shm_destroy(SharedMemory *shm) {
  if (!shm)
    return;
  if (shm->data && shm->data != (void *)-1)
    shmdt(shm->data);
  if (shmctl(shm->shm_id, IPC_RMID, NULL) < 0)
    WARN("Failed to remove shared memory: %s (may already be removed)",
         strerror(errno));
  else
    INFO("Shared memory destroyed (id: %d)", shm->shm_id);
  free(shm);
}

/**
 * 加锁共享内存
 */
void shm_lock(SharedMemory *shm) {
  if (shm && shm->data)
    pthread_mutex_lock(&shm->data->lock);
}

/**
 * 解锁共享内存
 */
void shm_unlock(SharedMemory *shm) {
  if (shm && shm->data)
    pthread_mutex_unlock(&shm->data->lock);
}

/**
 * 获取房间信息指针
 */
RoomInfo *shm_get_room(SharedMemory *shm, int room_id) {
  if (!shm || !shm->data || room_id < 0 || room_id >= MAX_ROOMS) {
    WARN("Invalid parameters to shm_get_room");
    return NULL;
  }
  return &shm->data->rooms[room_id];
}

/**
 * 更新房间信息
 */
void shm_update_room(SharedMemory *shm, int room_id, RoomInfo *info) {
  if (!shm || !shm->data || !info || room_id < 0 || room_id >= MAX_ROOMS) {
    WARN("Invalid parameters to shm_update_room");
    return;
  }
  shm_lock(shm);
  shm->data->rooms[room_id] = *info;
  shm->data->rooms[room_id].last_activity = time(NULL);
  shm->data->last_updated = time(NULL);
  shm_unlock(shm);
  DBG("Room %d updated", room_id);
}

/**
 * 查找用户（返回用户指针，调用者需在锁外使用或立即复制）
 */
User *shm_find_user(SharedMemory *shm, const char *username) {
  if (!shm || !shm->data || !username)
    return NULL;

  shm_lock(shm);
  for (int i = 0; i < shm->data->user_count; i++) {
    if (strcmp(shm->data->users[i].name, username) == 0) {
      shm_unlock(shm);
      return &shm->data->users[i];
    }
  }
  shm_unlock(shm);
  return NULL;
}

/**
 * 添加用户
 */
int shm_add_user(SharedMemory *shm, User *user) {
  if (!shm || !shm->data || !user) {
    ERROR("Invalid parameters to shm_add_user");
    return -1;
  }

  shm_lock(shm);
  if (shm->data->user_count >= MAX_USERS) {
    shm_unlock(shm);
    ERROR("User limit reached (%d)", MAX_USERS);
    return -1;
  }

  for (int i = 0; i < shm->data->user_count; i++) {
    if (strcmp(shm->data->users[i].name, user->name) == 0) {
      shm_unlock(shm);
      ERROR("User %s already exists", user->name);
      return -1;
    }
  }

  shm->data->users[shm->data->user_count] = *user;
  shm->data->user_count++;
  shm->data->last_updated = time(NULL);
  shm_unlock(shm);

  DBG("User %s added, total users: %d", user->name, shm->data->user_count);
  return 0;
}

/**
 * 更新用户信息
 */
void shm_update_user(SharedMemory *shm, const char *username, User *user) {
  if (!shm || !shm->data || !username || !user)
    return;

  shm_lock(shm);
  for (int i = 0; i < shm->data->user_count; i++) {
    if (strcmp(shm->data->users[i].name, username) == 0) {
      shm->data->users[i] = *user;
      shm->data->users[i].last_active = time(NULL);
      shm->data->last_updated = time(NULL);
      shm_unlock(shm);
      DBG("User %s updated", username);
      return;
    }
  }
  shm_unlock(shm);
  WARN("User %s not found for update", username);
}

/**
 * 获取总用户数
 */
int shm_get_user_count(SharedMemory *shm) {
  if (!shm || !shm->data)
    return 0;
  shm_lock(shm);
  int count = shm->data->user_count;
  shm_unlock(shm);
  return count;
}

/**
 * 获取在线用户数
 */
int shm_get_online_user_count(SharedMemory *shm) {
  if (!shm || !shm->data)
    return 0;

  shm_lock(shm);
  int count = 0;
  for (int i = 0; i < shm->data->user_count; i++) {
    if (shm->data->users[i].online)
      count++;
  }
  shm_unlock(shm);
  return count;
}
