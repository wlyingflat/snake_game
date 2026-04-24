/**
 * room_manager.c - 房间管理器（优化精简版）
 *
 * 功能：管理游戏房间的生命周期（创建、加入、离开、关闭），与共享内存交互。
 */

#include "room_manager.h"
#include "debug.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* ---------------------------- 静态函数声明 ---------------------------- */
static pid_t start_room_server(int room_id, int port);
static void stop_room_server(int room_id, pid_t pid);
static int is_room_process_alive(pid_t pid);
static void cleanup_room_if_dead(RoomInfo *room);

/* ---------------------------- 函数实现 ---------------------------- */

/**
 * 检查房间进程是否存活
 */
static int is_room_process_alive(pid_t pid) {
  return (pid > 0 && kill(pid, 0) == 0);
}

/**
 * 清理已死亡进程的房间状态（必须在共享内存锁内调用）
 */
static void cleanup_room_if_dead(RoomInfo *room) {
  if (room->process_id > 0 && !is_room_process_alive(room->process_id)) {
    WARN("Room %d has dead process (PID: %d), cleaning up", room->room_id,
         room->process_id);
    room->process_id = 0;
    room->player_count = 0;
    room->status = ROOM_CLOSED;
    room->last_activity = time(NULL);
  }
}

/**
 * 创建房间管理器
 */
RoomManager *room_manager_create(SharedMemory *shm, int base_port) {
  if (!shm || base_port <= 0) {
    ERROR("Invalid parameters to room_manager_create");
    return NULL;
  }

  RoomManager *manager = malloc(sizeof(RoomManager));
  if (!manager) {
    ERROR("Failed to allocate room manager");
    return NULL;
  }

  manager->shm = shm;
  manager->base_port = base_port;
  INFO("Room manager created with base port %d", base_port);
  return manager;
}

/**
 * 销毁房间管理器（停止所有房间进程）
 */
void room_manager_destroy(RoomManager *manager) {
  if (!manager)
    return;
  INFO("Destroying room manager");

  shm_lock(manager->shm);
  for (int i = 0; i < MAX_ROOMS; i++) {
    RoomInfo *room = &manager->shm->data->rooms[i];
    if (room->process_id > 0)
      stop_room_server(i, room->process_id);
  }
  shm_unlock(manager->shm);
  free(manager);
}

/**
 * 创建房间
 */
int room_create(RoomManager *manager, int room_id, const char *creator) {
  if (!manager || room_id < 0 || room_id >= MAX_ROOMS || !creator) {
    ERROR("Invalid parameters to room_create");
    return 0;
  }

  DBG("Creating room %d for user %s", room_id, creator);
  shm_lock(manager->shm);

  RoomInfo *room = &manager->shm->data->rooms[room_id];
  cleanup_room_if_dead(room);

  if (room->status != ROOM_CLOSED) {
    shm_unlock(manager->shm);
    ERROR("Room %d already exists and is active", room_id);
    return 0;
  }

  int port = manager->base_port + room_id;
  pid_t pid = start_room_server(room_id, port);
  if (pid <= 0) {
    shm_unlock(manager->shm);
    ERROR("Failed to start room server for room %d", room_id);
    return 0;
  }

  room->room_id = room_id;
  room->process_id = pid;
  room->port = port;
  room->player_count = 1;
  room->max_players = MAX_PLAYERS_PER_ROOM;
  room->status = ROOM_OPEN;
  room->created_at = time(NULL);
  room->last_activity = time(NULL);

  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  INFO("Room %d created on port %d (PID: %d)", room_id, port, pid);
  return 1;
}

/**
 * 加入房间
 */
int room_join(RoomManager *manager, int room_id, const char *username) {
  if (!manager || room_id < 0 || room_id >= MAX_ROOMS || !username) {
    ERROR("Invalid parameters to room_join");
    return 0;
  }

  DBG("User %s joining room %d", username, room_id);
  shm_lock(manager->shm);

  RoomInfo *room = &manager->shm->data->rooms[room_id];
  cleanup_room_if_dead(room);

  if (room->status == ROOM_CLOSED) {
    shm_unlock(manager->shm);
    ERROR("Room %d does not exist", room_id);
    return 0;
  }

  if (room->player_count >= room->max_players) {
    room->status = ROOM_FULL;
    shm_unlock(manager->shm);
    ERROR("Room %d is full", room_id);
    return 0;
  }

  room->player_count++;
  room->last_activity = time(NULL);
  room->status =
      (room->player_count >= room->max_players) ? ROOM_FULL : ROOM_OPEN;
  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  INFO("User %s joined room %d, players: %d/%d", username, room_id,
       room->player_count, room->max_players);
  return 1;
}

/**
 * 离开房间
 */
int room_leave(RoomManager *manager, int room_id, const char *username) {
  if (!manager || room_id < 0 || room_id >= MAX_ROOMS) {
    ERROR("Invalid parameters to room_leave");
    return 0;
  }

  DBG("User %s leaving room %d", username ? username : "unknown", room_id);
  shm_lock(manager->shm);

  RoomInfo *room = &manager->shm->data->rooms[room_id];
  cleanup_room_if_dead(room);

  if (room->status == ROOM_CLOSED) {
    shm_unlock(manager->shm);
    WARN("Room %d already closed", room_id);
    return 1; // 离开操作视为成功
  }

  if (room->player_count > 0)
    room->player_count--;
  room->last_activity = time(NULL);

  if (room->player_count <= 0) {
    if (room->process_id > 0)
      stop_room_server(room_id, room->process_id);
    room->process_id = 0;
    room->status = ROOM_CLOSED;
  } else {
    room->status =
        (room->player_count >= room->max_players) ? ROOM_FULL : ROOM_OPEN;
  }

  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  INFO("User left room %d, players: %d/%d", room_id, room->player_count,
       room->max_players);
  return 1;
}

/**
 * 关闭房间
 */
int room_close(RoomManager *manager, int room_id) {
  if (!manager || room_id < 0 || room_id >= MAX_ROOMS) {
    ERROR("Invalid parameters to room_close");
    return 0;
  }

  DBG("Closing room %d", room_id);
  shm_lock(manager->shm);

  RoomInfo *room = &manager->shm->data->rooms[room_id];
  cleanup_room_if_dead(room);

  if (room->status == ROOM_CLOSED) {
    shm_unlock(manager->shm);
    WARN("Room %d already closed", room_id);
    return 1;
  }

  if (room->process_id > 0)
    stop_room_server(room_id, room->process_id);
  room->process_id = 0;
  room->player_count = 0;
  room->status = ROOM_CLOSED;
  room->last_activity = time(NULL);

  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  INFO("Room %d closed", room_id);
  return 1;
}

/**
 * 获取房间列表字符串（动态分配）
 */
char *room_list(RoomManager *manager) {
  if (!manager)
    return NULL;

  char *buffer = malloc(BUFFER_SIZE);
  if (!buffer) {
    ERROR("Failed to allocate buffer for room list");
    return NULL;
  }

  int len = 0;
  shm_lock(manager->shm);

  len += snprintf(buffer + len, BUFFER_SIZE - len, "=== Room List ===\n");
  len += snprintf(buffer + len, BUFFER_SIZE - len,
                  "ID  Status  Players  Port    Created    Alive\n");
  len += snprintf(buffer + len, BUFFER_SIZE - len,
                  "--- ------- ------- ------- ---------- ------\n");

  int active = 0;
  for (int i = 0; i < MAX_ROOMS; i++) {
    RoomInfo *room = &manager->shm->data->rooms[i];
    if (room->status == ROOM_CLOSED)
      continue;

    int alive = is_room_process_alive(room->process_id);
    const char *status_str = (room->status == ROOM_OPEN) ? "OPEN" : "FULL";
    char time_buf[32];
    strftime(time_buf, sizeof(time_buf), "%H:%M:%S",
             localtime(&room->created_at));

    len += snprintf(buffer + len, BUFFER_SIZE - len,
                    "%-3d %-7s %2d/%-4d %-7d %-10s %s\n", room->room_id,
                    status_str, room->player_count, room->max_players,
                    room->port, time_buf, alive ? "YES" : "NO");
    active++;
  }

  if (active == 0) {
    len += snprintf(buffer + len, BUFFER_SIZE - len,
                    "No active rooms. Use CREATE <room_id> to create one.\n");
  } else {
    len += snprintf(buffer + len, BUFFER_SIZE - len,
                    "\nUse JOIN <room_id> to enter a room.\n");
  }

  shm_unlock(manager->shm);
  DBG("Generated room list with %d active rooms", active);
  return buffer;
}

/**
 * 获取房间状态
 */
RoomStatus room_get_status(RoomManager *manager, int room_id) {
  if (!manager || room_id < 0 || room_id >= MAX_ROOMS)
    return ROOM_CLOSED;

  shm_lock(manager->shm);
  RoomInfo *room = &manager->shm->data->rooms[room_id];
  cleanup_room_if_dead(room);
  RoomStatus status = room->status;
  shm_unlock(manager->shm);
  return status;
}

/**
 * 获取房间内玩家数量
 */
int room_get_player_count(RoomManager *manager, int room_id) {
  if (!manager || room_id < 0 || room_id >= MAX_ROOMS)
    return 0;

  shm_lock(manager->shm);
  RoomInfo *room = &manager->shm->data->rooms[room_id];
  cleanup_room_if_dead(room);
  int count = room->player_count;
  shm_unlock(manager->shm);
  return count;
}

/* ---------------------------- 房间进程管理 ---------------------------- */

/**
 * 启动房间服务器进程
 */
static pid_t start_room_server(int room_id, int port) {
  pid_t pid = fork();
  if (pid < 0) {
    ERROR("Failed to fork room server: %s", strerror(errno));
    return -1;
  }

  if (pid == 0) { // 子进程
    char id_str[16], port_str[16];
    snprintf(id_str, sizeof(id_str), "%d", room_id);
    snprintf(port_str, sizeof(port_str), "%d", port);
    execl("./room_server", "./room_server", id_str, port_str, NULL);
    ERROR("Failed to execute room server: %s", strerror(errno));
    exit(EXIT_FAILURE);
  }

  return pid;
}

/**
 * 停止房间服务器进程（PID 由调用者提供，必须有效）
 */
static void stop_room_server(int room_id, pid_t pid) {
  if (pid <= 0)
    return;

  DBG("Stopping room %d process (PID: %d)", room_id, pid);
  if (kill(pid, SIGTERM) < 0 && errno != ESRCH) {
    ERROR("Failed to send SIGTERM to room %d process: %s", room_id,
          strerror(errno));
  }
  waitpid(pid, NULL, WNOHANG);
  INFO("Room %d process stopped (PID: %d)", room_id, pid);
}
