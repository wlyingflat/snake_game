/**
 * gateway.c - 推送网关（使用统一日志）
 *
 * 功能：维护客户端长连接，从共享内存读取房间列表，在主服务器通知时广播更新。
 * 修改：generate_room_list 只生成数据行（无表头），减少传输量。
 */

#include "config.h"
#include "debug.h"
#include "network.h"
#include "shm_manager.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

/* ---------------------------- 常量 ---------------------------- */
#define MAX_CLIENTS 1024
#define PIPE_BUFFER_SIZE 64
#define HEARTBEAT_CHECK_INT 5 /* 心跳检查间隔（秒） */
#define ROOM_UPDATE_PREFIX "ROOM_LIST_UPDATE|"

/* ---------------------------- 客户端结构 ---------------------------- */
typedef struct {
  int fd;
  char username[USERNAME_LEN];
  time_t last_heartbeat;
} Client;

static Client clients[MAX_CLIENTS];
static int client_count = 0;
static SharedMemory *shm = NULL;
static int epoll_fd = -1;
static int listen_fd = -1;
static int unix_fd = -1;
static volatile int running = 1;

/* ---------------------------- 函数声明 ---------------------------- */
static char *generate_room_list(void);
static void send_room_list_to_client(int fd);
static void broadcast_room_list(void);
static void remove_client(int fd);
static void handle_client_message(int fd, char *buffer, int len);
static void check_heartbeats(void);
static void handle_pipe(int pipe_fd);
static void sig_handler(int sig);

/* ---------------------------- 函数实现 ---------------------------- */

/**
 * 从共享内存生成房间列表字符串（仅数据行，无表头）
 * 需调用者 free()
 */
static char *generate_room_list(void) {
  char *buffer = malloc(BUFFER_SIZE);
  if (!buffer) {
    ERROR("Failed to allocate buffer for room list");
    return NULL;
  }

  int len = 0;
  shm_lock(shm);

  for (int i = 0; i < MAX_ROOMS; i++) {
    RoomInfo *room = &shm->data->rooms[i];
    if (room->status != ROOM_CLOSED) {
      const char *status_str = (room->status == ROOM_OPEN) ? "OPEN" : "FULL";
      char time_buf[32];
      struct tm *tm_info = localtime(&room->created_at);
      strftime(time_buf, sizeof(time_buf), "%H:%M:%S", tm_info);

      len +=
          snprintf(buffer + len, BUFFER_SIZE - len,
                   "%-3d %-7s %2d/%-4d %-7d %-10s\n", room->room_id, status_str,
                   room->player_count, room->max_players, room->port, time_buf);
    }
  }

  if (len == 0) {
    len += snprintf(buffer + len, BUFFER_SIZE - len, "No active rooms.\n");
  }

  shm_unlock(shm);
  DBG("Generated room list data (%d bytes)", len);
  return buffer;
}

/**
 * 向单个客户端发送房间列表
 */
static void send_room_list_to_client(int fd) {
  char *list = generate_room_list();
  if (!list)
    return;

  char msg[BUFFER_SIZE + sizeof(ROOM_UPDATE_PREFIX)];
  snprintf(msg, sizeof(msg), "%s%s", ROOM_UPDATE_PREFIX, list);
  free(list);

  if (send_message(fd, msg) < 0) {
    WARN("Failed to send room list to fd=%d (%s), will remove", fd,
         strerror(errno));
    close(fd);
    remove_client(fd);
  }
}

/**
 * 向所有客户端广播房间列表
 */
static void broadcast_room_list(void) {
  int original_count = client_count;
  int success_count = 0;

  char *list = generate_room_list();
  if (!list) {
    ERROR("Failed to generate room list for broadcast");
    return;
  }

  char msg[BUFFER_SIZE + sizeof(ROOM_UPDATE_PREFIX)];
  snprintf(msg, sizeof(msg), "%s%s", ROOM_UPDATE_PREFIX, list);
  free(list);

  for (int i = 0; i < client_count;) {
    int fd = clients[i].fd;
    if (send_message(fd, msg) < 0) {
      WARN("Failed to send to fd=%d (%s), will remove", fd, strerror(errno));
      close(fd);
      remove_client(fd);
    } else {
      success_count++;
      i++;
    }
  }
  INFO("Broadcasted room list to %d/%d clients", success_count, original_count);
}

/**
 * 移除客户端
 */
static void remove_client(int fd) {
  for (int i = 0; i < client_count; i++) {
    if (clients[i].fd == fd) {
      char username[USERNAME_LEN];
      strcpy(username, clients[i].username);
      clients[i] = clients[--client_count];
      INFO("Client fd=%d (user=%s) removed, remaining: %d", fd, username,
           client_count);
      return;
    }
  }
}

/**
 * 处理客户端消息
 */
static void handle_client_message(int fd, char *buffer, int len) {
  buffer[len] = '\0';

  Client *client = NULL;
  for (int i = 0; i < client_count; i++) {
    if (clients[i].fd == fd) {
      client = &clients[i];
      break;
    }
  }
  if (!client) {
    WARN("Received message from unknown fd=%d, closing", fd);
    close(fd);
    return;
  }

  client->last_heartbeat = time(NULL);
  DBG("Received from fd=%d (%s): %s", fd, client->username, buffer);

  if (strncmp(buffer, "USER ", 5) == 0) {
    char username[USERNAME_LEN];
    sscanf(buffer + 5, "%31s", username);
    strncpy(client->username, username, USERNAME_LEN - 1);
    client->username[USERNAME_LEN - 1] = '\0';
    INFO("Client fd=%d identified as %s", fd, username);
    send_room_list_to_client(fd);
  } else if (strcmp(buffer, "PING") == 0) {
    send_message(fd, "PONG");
    DBG("Sent PONG to fd=%d", fd);
  } else if (strcmp(buffer, "QUIT") == 0) {
    INFO("Client fd=%d quit", fd);
    close(fd);
    remove_client(fd);
  } else if (strcmp(buffer, "ROOM_LIST") == 0) {
    send_room_list_to_client(fd);
    DBG("Sent room list on request to fd=%d", fd);
  } else {
    WARN("Unknown message from fd=%d: %s", fd, buffer);
  }
}

/**
 * 心跳检查
 */
static void check_heartbeats(void) {
  time_t now = time(NULL);
  for (int i = 0; i < client_count;) {
    if (now - clients[i].last_heartbeat > HEARTBEAT_TIMEOUT) {
      WARN("Client fd=%d (user=%s) heartbeat timeout", clients[i].fd,
           clients[i].username);
      close(clients[i].fd);
      remove_client(clients[i].fd);
    } else {
      i++;
    }
  }
}

/**
 * 处理来自主服务器的管道信号
 */
static void handle_pipe(int pipe_fd) {
  char buf[PIPE_BUFFER_SIZE];
  int r = read(pipe_fd, buf, sizeof(buf) - 1);
  if (r <= 0) {
    if (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
      ERROR("Pipe read error: %s", strerror(errno));
    return;
  }
  buf[r] = '\0';
  DBG("Received from main server: %s", buf);
  if (strstr(buf, "REFRESH") != NULL) {
    INFO("Received REFRESH from main server, broadcasting...");
    broadcast_room_list();
  }
}

/**
 * 信号处理
 */
static void sig_handler(int sig) {
  INFO("Received signal %d, shutting down...", sig);
  running = 0;
}

/* ---------------------------- 主函数 ---------------------------- */
int main(int argc, char **argv) {
  if (argc != 3) {
    fprintf(stderr, "Usage: %s <gateway_port> <pipe_read_fd>\n", argv[0]);
    return EXIT_FAILURE;
  }

  int port = atoi(argv[1]);
  int pipe_fd = atoi(argv[2]);

  signal(SIGINT, sig_handler);
  signal(SIGTERM, sig_handler);

  INFO("Starting gateway on port %d, pipe fd=%d", port, pipe_fd);

  shm = shm_attach();
  if (!shm) {
    ERROR("Failed to attach to shared memory");
    return EXIT_FAILURE;
  }
  INFO("Attached to shared memory (id=%d)", shm->shm_id);

  listen_fd = server_create(port);
  if (listen_fd < 0) {
    ERROR("Failed to create listening socket");
    return EXIT_FAILURE;
  }
  server_set_nonblocking(listen_fd);
  INFO("Listening socket created (fd=%d)", listen_fd);

  unix_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
  if (unix_fd < 0) {
    ERROR("Failed to create Unix socket: %s", strerror(errno));
  } else {
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, GATEWAY_SOCK_PATH, sizeof(addr.sun_path) - 1);
    unlink(GATEWAY_SOCK_PATH);

    if (bind(unix_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
      ERROR("Failed to bind Unix socket: %s", strerror(errno));
      close(unix_fd);
      unix_fd = -1;
    } else {
      server_set_nonblocking(unix_fd);
      INFO("Unix socket created at %s (fd=%d)", GATEWAY_SOCK_PATH, unix_fd);
    }
  }

  epoll_fd = epoll_create1(0);
  if (epoll_fd < 0) {
    ERROR("epoll_create1 failed: %s", strerror(errno));
    return EXIT_FAILURE;
  }

  struct epoll_event ev;
  ev.events = EPOLLIN;
  ev.data.fd = listen_fd;
  if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, listen_fd, &ev) < 0) {
    ERROR("Failed to add listen fd to epoll: %s", strerror(errno));
    return EXIT_FAILURE;
  }

  ev.data.fd = pipe_fd;
  if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, pipe_fd, &ev) < 0) {
    ERROR("Failed to add pipe fd to epoll: %s", strerror(errno));
    return EXIT_FAILURE;
  }

  if (unix_fd >= 0) {
    ev.data.fd = unix_fd;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, unix_fd, &ev) < 0) {
      ERROR("Failed to add Unix fd to epoll: %s", strerror(errno));
      close(unix_fd);
      unix_fd = -1;
    }
  }

  INFO("Gateway is ready and listening");

  struct epoll_event events[MAX_EVENTS];
  time_t last_heartbeat_check = time(NULL);

  while (running) {
    int n = epoll_wait(epoll_fd, events, MAX_EVENTS, 1000);
    if (n < 0) {
      if (errno == EINTR)
        continue;
      ERROR("epoll_wait error: %s", strerror(errno));
      break;
    }

    for (int i = 0; i < n; i++) {
      int fd = events[i].data.fd;

      if (fd == listen_fd) {
        int client_fd = server_accept(listen_fd, NULL);
        if (client_fd >= 0) {
          server_set_nonblocking(client_fd);
          ev.events = EPOLLIN | EPOLLET;
          ev.data.fd = client_fd;
          if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, client_fd, &ev) < 0) {
            ERROR("Failed to add client fd to epoll: %s", strerror(errno));
            close(client_fd);
            continue;
          }
          if (client_count < MAX_CLIENTS) {
            clients[client_count].fd = client_fd;
            clients[client_count].last_heartbeat = time(NULL);
            clients[client_count].username[0] = '\0';
            client_count++;
            INFO("New client fd=%d, total=%d", client_fd, client_count);
          } else {
            WARN("Max clients reached, rejecting fd=%d", client_fd);
            close(client_fd);
          }
        }
      } else if (fd == pipe_fd) {
        handle_pipe(pipe_fd);
      } else if (fd == unix_fd) {
        char buf[64];
        struct sockaddr_un sender;
        socklen_t sender_len = sizeof(sender);
        ssize_t n = recvfrom(fd, buf, sizeof(buf), 0,
                             (struct sockaddr *)&sender, &sender_len);
        if (n > 0) {
          DBG("Received notification from room server, broadcasting room list");
          broadcast_room_list();
        } else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
          ERROR("recvfrom on Unix socket failed: %s", strerror(errno));
        }
      } else {
        char buffer[BUFFER_SIZE];
        int r = receive_message(fd, buffer, sizeof(buffer), 0);
        if (r < 0) {
          if (errno == EAGAIN || errno == EWOULDBLOCK) {
            continue;
          } else {
            ERROR("Receive error on fd=%d: %s", fd, strerror(errno));
            epoll_ctl(epoll_fd, EPOLL_CTL_DEL, fd, NULL);
            close(fd);
            remove_client(fd);
          }
        } else if (r == 0) {
          INFO("Client fd=%d closed connection", fd);
          epoll_ctl(epoll_fd, EPOLL_CTL_DEL, fd, NULL);
          close(fd);
          remove_client(fd);
        } else {
          handle_client_message(fd, buffer, r);
        }
      }
    }

    time_t now = time(NULL);
    if (now - last_heartbeat_check >= HEARTBEAT_CHECK_INT) {
      check_heartbeats();
      last_heartbeat_check = now;
    }
  }

  INFO("Shutting down gateway, cleaning up %d clients", client_count);
  for (int i = 0; i < client_count; i++) {
    close(clients[i].fd);
  }
  close(listen_fd);
  close(pipe_fd);
  if (unix_fd >= 0) {
    close(unix_fd);
    unlink(GATEWAY_SOCK_PATH);
  }
  close(epoll_fd);
  shm_detach(shm);
  INFO("Gateway shutdown complete");
  return 0;
}
