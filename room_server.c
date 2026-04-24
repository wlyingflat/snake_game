/**
 * room_server.c - 房间服务器（优化精简版）
 *
 * 功能：每个游戏房间的独立进程，处理玩家连接、游戏逻辑更新、状态广播。
 */

#include "config.h"
#include "debug.h"
#include "game_world.h"
#include "network.h"
#include "shm_manager.h"
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/un.h> // 新增：Unix域套接字
#include <sys/wait.h>
#include <unistd.h>

/* ---------------------------- 全局变量 ---------------------------- */
static GameManager *game_manager = NULL;
static SharedMemory *shm = NULL;
static int epoll_fd = -1;
static int server_fd = -1;
static volatile int running = 1;
static int room_id = -1;
static int notify_fd = -1; // 新增：用于通知网关的Unix套接字

/* ---------------------------- 函数声明（静态） ---------------------------- */
static void *tick_thread(void *arg);
static void handle_player_input(int socket_fd);
static void handle_new_connection(int client_fd);
static void cleanup(void);
static void signal_handler(int sig);
static int has_connected_players(GameManager *gm);
static void broadcast_game_state(GameManager *gm);
static void notify_gateway(void); // 新增：发送通知

/* ---------------------------- 函数实现 ---------------------------- */

/**
 * 通知网关房间信息已变化
 */
static void notify_gateway(void) {
  if (notify_fd >= 0) {
    // 发送一个字节，内容任意
    if (send(notify_fd, "U", 1, 0) < 0 && errno != EAGAIN) {
      WARN("Failed to notify gateway: %s", strerror(errno));
    }
  }
}

/**
 * 检查是否有活跃的玩家连接
 */
static int has_connected_players(GameManager *gm) {
  pthread_rwlock_rdlock(&gm->world.lock);
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (gm->world.players[i].is_used && gm->world.players[i].socket_fd > 0) {
      pthread_rwlock_unlock(&gm->world.lock);
      return 1;
    }
  }
  pthread_rwlock_unlock(&gm->world.lock);
  return 0;
}

/**
 * 广播游戏状态给所有存活玩家
 */
static void broadcast_game_state(GameManager *gm) {
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (gm->world.players[i].is_used && !gm->world.players[i].is_dead) {
      GameStateData state_data;
      if (game_get_state_data(gm, &state_data,
                              gm->world.players[i].socket_fd) == 0) {
        char *serialized = serialize_game_state(&state_data);
        if (serialized) {
          send_message(gm->world.players[i].socket_fd, serialized);
          free(serialized);
        }
      }
    }
  }
}

/**
 * tick线程函数，定时更新游戏世界并广播状态
 */
static void *tick_thread(void *arg) {
  GameManager *gm = (GameManager *)arg;
  int tick_count = 0;

  INFO("Tick thread started for room %d", gm->room_id);

  while (running) {
    usleep(TICK_INTERVAL_MS * 1000);

    if (game_should_shutdown(gm)) {
      if (!has_connected_players(gm)) {
        INFO("Room %d has no connected players, shutting down", gm->room_id);
        running = 0;
        break;
      }
      pthread_rwlock_wrlock(&gm->world.lock);
      gm->world.should_shutdown = 0;
      pthread_rwlock_unlock(&gm->world.lock);
    }

    tick_count++;
    if (tick_count <= ROOM_INIT_DELAY_TICKS) {
      DBG("Initial delay tick %d/%d", tick_count, ROOM_INIT_DELAY_TICKS);
      continue;
    }

    time_t now = time(NULL);
    if (gm->world.total_players == 0 && gm->world.initial_delay_done &&
        (now - gm->last_tick_time) > ROOM_IDLE_TIMEOUT) {
      INFO("Room %d idle timeout, shutting down", gm->room_id);
      running = 0;
      break;
    }

    game_update_world(gm);
    gm->last_tick_time = now;
    broadcast_game_state(gm);
  }

  INFO("Tick thread for room %d exiting", gm->room_id);
  return NULL;
}

/**
 * 处理玩家输入（方向、退出等）
 */
static void handle_player_input(int socket_fd) {
  char buffer[32];
  int r = receive_message(socket_fd, buffer, sizeof(buffer), 0);

  if (r <= 0) {
    if (r == 0)
      INFO("Player disconnected on fd=%d", socket_fd);
    else if (errno != EAGAIN && errno != EWOULDBLOCK)
      ERROR("Receive error on fd=%d: %s", socket_fd, strerror(errno));
    game_remove_player(game_manager, socket_fd);
    notify_gateway(); // 新增：玩家离开，通知网关刷新列表
    return;
  }

  for (int i = 0; i < r; i++) {
    switch (buffer[i]) {
    case 'w':
    case 'W':
      game_update_player_direction(game_manager, socket_fd, DIR_UP);
      break;
    case 's':
    case 'S':
      game_update_player_direction(game_manager, socket_fd, DIR_DOWN);
      break;
    case 'a':
    case 'A':
      game_update_player_direction(game_manager, socket_fd, DIR_LEFT);
      break;
    case 'd':
    case 'D':
      game_update_player_direction(game_manager, socket_fd, DIR_RIGHT);
      break;
    case 'q':
    case 'Q':
      INFO("Player requested quit on fd=%d", socket_fd);
      game_remove_player(game_manager, socket_fd);
      notify_gateway(); // 新增：玩家主动退出，通知网关
      return;
    default:
      break;
    }
  }
}

/**
 * 处理新客户端连接
 */
static void handle_new_connection(int client_fd) {
  char buffer[BUFFER_SIZE];
  int r =
      receive_message(client_fd, buffer, sizeof(buffer), RECV_TIMEOUT * 1000);
  if (r <= 0) {
    ERROR("Failed to receive player info");
    close(client_fd);
    return;
  }

  char username[USERNAME_LEN];
  if (sscanf(buffer, "PLAYER %31s", username) != 1) {
    send_message(client_fd, "ERROR Invalid player format");
    close(client_fd);
    return;
  }

  INFO("Player %s joining room %d", username, room_id);
  if (game_add_player(game_manager, client_fd, username) != 0) {
    send_message(client_fd, "ERROR Cannot add player");
    close(client_fd);
    return;
  }

  notify_gateway(); // 新增：新玩家加入，通知网关刷新列表

  server_set_nonblocking(client_fd);

  struct epoll_event ev = {.events = EPOLLIN | EPOLLET, .data.fd = client_fd};
  if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, client_fd, &ev) < 0) {
    ERROR("Failed to add player to epoll: %s", strerror(errno));
    game_remove_player(game_manager, client_fd);
    notify_gateway(); // 新增：添加失败也通知（实际已移除）
    return;
  }

  char welcome[128];
  snprintf(welcome, sizeof(welcome), "WELCOME TO ROOM %d", room_id);
  send_message(client_fd, welcome);

  GameStateData state_data;
  if (game_get_state_data(game_manager, &state_data, client_fd) == 0) {
    char *serialized = serialize_game_state(&state_data);
    if (serialized) {
      send_message(client_fd, serialized);
      free(serialized);
    }
  }
}

/**
 * 清理资源
 */
static void cleanup(void) {
  INFO("Cleaning up room server %d...", room_id);

  if (shm && room_id >= 0) {
    shm_lock(shm);
    RoomInfo *room = &shm->data->rooms[room_id];
    if (room->process_id > 0) {
      DBG("Clearing room %d process info in shared memory", room_id);
      room->process_id = 0;
      room->player_count = 0;
      room->status = ROOM_CLOSED;
      room->last_activity = time(NULL);
      shm->data->last_updated = time(NULL);
      INFO("Room %d marked as CLOSED in shared memory", room_id);
    }
    shm_unlock(shm);
  }

  notify_gateway(); // 新增：房间关闭，通知网关刷新列表

  running = 0;
  if (epoll_fd >= 0)
    close(epoll_fd);
  if (server_fd >= 0)
    close(server_fd);

  if (game_manager) {
    for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
      if (game_manager->world.players[i].is_used &&
          game_manager->world.players[i].socket_fd > 0)
        close(game_manager->world.players[i].socket_fd);
    }
    game_manager_destroy(game_manager);
    game_manager = NULL;
  }

  if (shm) {
    shm_detach(shm);
    shm = NULL;
  }

  if (notify_fd >= 0) {
    close(notify_fd);
    notify_fd = -1;
  }

  INFO("Room server %d cleanup complete", room_id);
}

/**
 * 信号处理函数
 */
static void signal_handler(int sig) {
  INFO("Room %d received signal %d, shutting down...", room_id, sig);
  running = 0;
}

/**
 * 主函数
 */
int main(int argc, char **argv) {
  if (argc !=
      3) { // 注意：原本是3，但现在需要接收通知fd，所以改为4？我们修改为4
    fprintf(stderr, "Usage: %s <room_id> <port>\n", argv[0]);
    return EXIT_FAILURE;
  }

  room_id = atoi(argv[1]);
  int port = atoi(argv[2]);
  INFO("Starting room server %d on port %d", room_id, port);

  signal(SIGINT, signal_handler);
  signal(SIGTERM, signal_handler);

  /* 新增：连接网关的Unix域套接字用于通知 */
  notify_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
  if (notify_fd < 0) {
    WARN("Failed to create Unix socket for gateway notification: %s",
         strerror(errno));
  } else {
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, GATEWAY_SOCK_PATH, sizeof(addr.sun_path) - 1);
    if (connect(notify_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
      WARN("Failed to connect to gateway Unix socket: %s", strerror(errno));
      close(notify_fd);
      notify_fd = -1;
    } else {
      server_set_nonblocking(notify_fd); // 可选
      INFO("Connected to gateway notification socket");
    }
  }

  shm = shm_attach();
  if (!shm)
    FATAL("Failed to attach to shared memory");

  game_manager = game_manager_create(room_id, shm);
  if (!game_manager)
    FATAL("Failed to create game manager");

  server_fd = server_create(port);
  if (server_fd < 0)
    FATAL("Failed to create server socket");

  epoll_fd = epoll_create1(0);
  if (epoll_fd < 0)
    FATAL("Failed to create epoll: %s", strerror(errno));

  struct epoll_event ev = {.events = EPOLLIN, .data.fd = server_fd};
  if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, server_fd, &ev) < 0)
    FATAL("Failed to add server to epoll: %s", strerror(errno));

  pthread_t tick_tid;
  if (pthread_create(&tick_tid, NULL, tick_thread, game_manager) != 0)
    FATAL("Failed to create tick thread");
  pthread_detach(tick_tid);

  INFO("Room server %d is ready and listening on port %d", room_id, port);

  struct epoll_event events[MAX_EVENTS];

  while (running) {
    int n = epoll_wait(epoll_fd, events, MAX_EVENTS, 1000);
    if (n < 0) {
      if (errno == EINTR)
        continue;
      ERROR("epoll_wait error: %s", strerror(errno));
      break;
    }

    for (int i = 0; i < n; i++) {
      if (events[i].data.fd == server_fd) {
        int client_fd = server_accept(server_fd, NULL);
        if (client_fd >= 0)
          handle_new_connection(client_fd);
      } else {
        handle_player_input(events[i].data.fd);
      }
    }

    if (game_should_shutdown(game_manager)) {
      INFO("Room %d shutdown requested in main loop", room_id);
      break;
    }
  }

  cleanup();
  INFO("Room server %d shutdown complete", room_id);
  return EXIT_SUCCESS;
}
