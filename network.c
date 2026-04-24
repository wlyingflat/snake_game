/**
 * network.c - 网络通信模块（优化版，已修复编译错误）
 *
 * 功能：提供TCP连接管理、数据收发、协议解析、游戏状态序列化等功能。
 *
 * 修改：在 connection_connect 中添加详细错误日志。
 */

#include "network.h"
#include "debug.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

/* ---------------------------- 连接管理 ---------------------------- */

Connection *connection_create(void) {
  Connection *conn = malloc(sizeof(Connection));
  if (!conn) {
    ERROR("Failed to allocate connection");
    return NULL;
  }
  memset(conn, 0, sizeof(Connection));
  conn->socket_fd = -1;
  return conn;
}

int connection_connect(Connection *conn, const char *ip, int port) {
  if (!conn || !ip || port <= 0) {
    ERROR("Invalid parameters");
    return -1;
  }

  conn->socket_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (conn->socket_fd < 0) {
    ERROR("socket() failed: %s", strerror(errno));
    return -1;
  }

  struct sockaddr_in *addr = &conn->address;
  memset(addr, 0, sizeof(*addr));
  addr->sin_family = AF_INET;
  addr->sin_port = htons(port);

  if (inet_pton(AF_INET, ip, &addr->sin_addr) <= 0) {
    ERROR("Invalid IP: %s", ip);
    goto error;
  }

  if (connect(conn->socket_fd, (struct sockaddr *)addr, sizeof(*addr)) < 0) {
    // ========== 修改点：增加详细错误日志 ==========
    ERROR("connect() to %s:%d failed: %s", ip, port, strerror(errno));
    // ========== 修改结束 ==========
    goto error;
  }

  conn->is_connected = 1;
  return 0;

error:
  close(conn->socket_fd);
  conn->socket_fd = -1;
  return -1;
}

void connection_close(Connection *conn) {
  if (!conn)
    return;
  if (conn->socket_fd >= 0) {
    close(conn->socket_fd);
    conn->socket_fd = -1;
  }
  conn->is_connected = 0;
}

void connection_destroy(Connection *conn) {
  if (!conn)
    return;
  connection_close(conn);
  free(conn);
}

/* ---------------------------- 服务器端 ---------------------------- */

int server_create(int port) {
  int server_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (server_fd < 0) {
    ERROR("socket() failed: %s", strerror(errno));
    return -1;
  }

  int opt = 1;
  if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
    ERROR("setsockopt(SO_REUSEADDR) failed: %s", strerror(errno));
    close(server_fd);
    return -1;
  }

  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = INADDR_ANY;
  addr.sin_port = htons(port);

  if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
    ERROR("bind() failed on port %d: %s", port, strerror(errno));
    close(server_fd);
    return -1;
  }

  if (listen(server_fd, BACKLOG) < 0) {
    ERROR("listen() failed: %s", strerror(errno));
    close(server_fd);
    return -1;
  }

  INFO("Server listening on port %d, fd=%d", port, server_fd);
  return server_fd;
}

int server_accept(int server_fd, struct sockaddr_in *client_addr) {
  if (server_fd < 0)
    return -1;

  socklen_t addr_len = sizeof(struct sockaddr_in);
  int client_fd = accept(server_fd, (struct sockaddr *)client_addr, &addr_len);
  if (client_fd < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
    ERROR("accept() failed: %s", strerror(errno));
  }
  return client_fd;
}

void server_set_nonblocking(int fd) {
  if (fd < 0)
    return;
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0) {
    ERROR("fcntl(F_GETFL) failed: %s", strerror(errno));
    return;
  }
  if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
    ERROR("fcntl(F_SETFL) failed: %s", strerror(errno));
  }
}

/* ---------------------------- 数据收发 ---------------------------- */

int send_message(int fd, const char *message) {
  if (fd < 0 || !message) {
    ERROR("Invalid parameters");
    return -1;
  }

  // 计算最终消息长度（原始长度 + 换行符 + 结束符）
  size_t len = strlen(message);
  char *buf = (char *)malloc(len + 2); // +1 for '\n', +1 for '\0'
  if (!buf) {
    ERROR("malloc failed");
    return -1;
  }

  snprintf(buf, len + 2, "%s\n", message);
  size_t total_len = len + 1; // 不包括 '\0'

  ssize_t sent = send(fd, buf, total_len, 0);
  free(buf);

  if (sent < 0) {
    ERROR("send() failed on fd=%d: %s", fd, strerror(errno));
    return -1;
  }
  if ((size_t)sent != total_len) {
    WARN("Partial send on fd=%d: %zd/%zu bytes", fd, sent, total_len);
  }
  return (int)sent;
}

int receive_message(int fd, char *buffer, size_t size, int timeout_ms) {
  if (fd < 0 || !buffer || size == 0) {
    ERROR("Invalid parameters");
    return -1;
  }

  if (timeout_ms > 0) {
    struct timeval tv = {.tv_sec = timeout_ms / 1000,
                         .tv_usec = (timeout_ms % 1000) * 1000};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
      ERROR("setsockopt(SO_RCVTIMEO) failed: %s", strerror(errno));
    }
  }

  ssize_t received = recv(fd, buffer, size - 1, 0);
  if (received > 0) {
    buffer[received] = '\0';
    // 移除末尾换行符（客户端可能发送）
    if (received > 0 && buffer[received - 1] == '\n') {
      buffer[--received] = '\0';
    }
  }

  // 恢复超时（可选，但不影响后续）
  if (timeout_ms > 0) {
    struct timeval tv = {0, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  }

  if (received < 0) {
    if (errno != EAGAIN && errno != EWOULDBLOCK) {
      ERROR("recv() failed: %s", strerror(errno));
    }
    return -1;
  }
  return (int)received; // 0 表示对方关闭
}

/* ---------------------------- 协议处理 ---------------------------- */

int parse_command(const char *buffer, char *cmd, char *arg1, char *arg2) {
  if (!buffer || !cmd)
    return 0;
  if (arg1)
    arg1[0] = '\0';
  if (arg2)
    arg2[0] = '\0';

  char tmp[BUFFER_SIZE];
  if (snprintf(tmp, sizeof(tmp), "%s", buffer) >= (int)sizeof(tmp)) {
    ERROR("Command too long");
    return 0;
  }

  char *saveptr;
  char *token = strtok_r(tmp, " ", &saveptr);
  if (!token)
    return 0;

  if (snprintf(cmd, 32, "%s", token) >= 32) {
    ERROR("Command truncated");
    return 0;
  }

  token = strtok_r(NULL, " ", &saveptr);
  if (token && arg1) {
    if (snprintf(arg1, USERNAME_LEN, "%s", token) >= USERNAME_LEN) {
      ERROR("Argument1 truncated");
      return 0;
    }
  }

  token = strtok_r(NULL, "", &saveptr);
  if (token && arg2) {
    if (snprintf(arg2, USERNAME_LEN, "%s", token) >= USERNAME_LEN) {
      ERROR("Argument2 truncated");
      return 0;
    }
  }
  return 1;
}

char *build_response(const char *type, const char *arg1, const char *arg2) {
  char *resp = malloc(BUFFER_SIZE);
  if (!resp) {
    ERROR("malloc failed");
    return NULL;
  }

  int len;
  if (arg1 && arg2) {
    len = snprintf(resp, BUFFER_SIZE, "%s %s %s\n", type, arg1, arg2);
  } else if (arg1) {
    len = snprintf(resp, BUFFER_SIZE, "%s %s\n", type, arg1);
  } else {
    len = snprintf(resp, BUFFER_SIZE, "%s\n", type);
  }

  if (len < 0 || len >= BUFFER_SIZE) {
    ERROR("Response too long or snprintf error");
    free(resp);
    return NULL;
  }
  return resp;
}

/* ---------------------------- 游戏状态序列化 ---------------------------- */

/**
 * 序列化游戏状态（动态分配）
 * 格式：STATE|room_id|food_x food_y|obstacle_count|(x y)*|player_count|
 *       (name:head_x head_y:length:direction:score:is_dead:is_you:(body_x
 * body_y)*:|)* |active_players total_players|
 */
char *serialize_game_state(GameStateData *state) {
  if (!state)
    return NULL;

  size_t size = 1024;
  char *buf = malloc(size);
  if (!buf)
    return NULL;

  int total = 0;
  int needed;

#define APPEND(fmt, ...)                                                       \
  do {                                                                         \
    needed = snprintf(buf + total, size - total, fmt, ##__VA_ARGS__);          \
    if (needed < 0) {                                                          \
      free(buf);                                                               \
      return NULL;                                                             \
    }                                                                          \
    if ((size_t)(total + needed) >= size) {                                    \
      size = total + needed + 1;                                               \
      char *new_buf = realloc(buf, size);                                      \
      if (!new_buf) {                                                          \
        free(buf);                                                             \
        return NULL;                                                           \
      }                                                                        \
      buf = new_buf;                                                           \
      needed = snprintf(buf + total, size - total, fmt, ##__VA_ARGS__);        \
      if (needed < 0) {                                                        \
        free(buf);                                                             \
        return NULL;                                                           \
      }                                                                        \
    }                                                                          \
    total += needed;                                                           \
  } while (0)

  APPEND("STATE|%d|%d %d|%d|", state->room_id, state->food.x, state->food.y,
         state->obstacle_count);

  for (int i = 0; i < state->obstacle_count; i++) {
    APPEND("%d %d ", state->obstacles[i].x, state->obstacles[i].y);
  }
  APPEND("|%d|", state->player_count);

  for (int i = 0; i < state->player_count; i++) {
    APPEND("%s:%d %d:%d:%d:%d:%d:%d:", state->players[i].name,
           state->players[i].head.x, state->players[i].head.y,
           state->players[i].length, state->players[i].direction,
           state->players[i].score, state->players[i].is_dead,
           state->players[i].is_you);
    for (int j = 0; j < state->players[i].length; j++) {
      APPEND("%d %d ", state->players[i].body[j].x,
             state->players[i].body[j].y);
    }
    APPEND("|");
  }

  APPEND("|%d %d|", state->active_players, state->total_players);

#undef APPEND

  if ((size_t)total >= size) {
    char *new_buf = realloc(buf, total + 1);
    if (!new_buf) {
      free(buf);
      return NULL;
    }
    buf = new_buf;
  }
  buf[total] = '\0';
  return buf;
}

/**
 * 反序列化游戏状态
 */
int deserialize_game_state(const char *buffer, GameStateData *state) {
  if (!buffer || !state)
    return -1;

  char tmp[BUFFER_SIZE];
  if (snprintf(tmp, sizeof(tmp), "%s", buffer) >= (int)sizeof(tmp)) {
    ERROR("Input too long");
    return -1;
  }

  char *saveptr1, *saveptr2, *saveptr3;
  char *token = strtok_r(tmp, "|", &saveptr1);

  if (!token || strcmp(token, "STATE") != 0)
    return -1;

  token = strtok_r(NULL, "|", &saveptr1);
  if (!token)
    return -1;
  state->room_id = atoi(token);

  token = strtok_r(NULL, "|", &saveptr1);
  if (!token)
    return -1;
  char *food_token = strtok_r(token, " ", &saveptr2);
  state->food.x = food_token ? atoi(food_token) : 0;
  food_token = strtok_r(NULL, " ", &saveptr2);
  state->food.y = food_token ? atoi(food_token) : 0;

  token = strtok_r(NULL, "|", &saveptr1);
  if (!token)
    return -1;
  state->obstacle_count = atoi(token);

  token = strtok_r(NULL, "|", &saveptr1);
  if (!token)
    return -1;
  char *obs_token = strtok_r(token, " ", &saveptr2);
  for (int i = 0; i < state->obstacle_count; i++) {
    if (!obs_token)
      break;
    state->obstacles[i].x = atoi(obs_token);
    obs_token = strtok_r(NULL, " ", &saveptr2);
    if (obs_token) {
      state->obstacles[i].y = atoi(obs_token);
      obs_token = strtok_r(NULL, " ", &saveptr2);
    }
  }

  token = strtok_r(NULL, "|", &saveptr1);
  if (!token)
    return -1;
  state->player_count = atoi(token);

  for (int i = 0; i < state->player_count; i++) {
    token = strtok_r(NULL, "|", &saveptr1);
    if (!token)
      break;

    char *player_token = strtok_r(token, ":", &saveptr2);
    if (!player_token)
      break;

    strncpy(state->players[i].name, player_token, USERNAME_LEN - 1);
    state->players[i].name[USERNAME_LEN - 1] = '\0';

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (!player_token)
      break;
    char *head_token = strtok_r(player_token, " ", &saveptr3);
    state->players[i].head.x = head_token ? atoi(head_token) : 0;
    head_token = strtok_r(NULL, " ", &saveptr3);
    state->players[i].head.y = head_token ? atoi(head_token) : 0;

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (!player_token)
      break;
    state->players[i].length = atoi(player_token);

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (!player_token)
      break;
    state->players[i].direction = atoi(player_token);

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (!player_token)
      break;
    state->players[i].score = atoi(player_token);

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (!player_token)
      break;
    state->players[i].is_dead = atoi(player_token);

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (!player_token)
      break;
    state->players[i].is_you = atoi(player_token);

    player_token = strtok_r(NULL, ":", &saveptr2);
    if (player_token) {
      char *body_token = strtok_r(player_token, " ", &saveptr3);
      for (int j = 0; j < state->players[i].length; j++) {
        if (!body_token)
          break;
        state->players[i].body[j].x = atoi(body_token);
        body_token = strtok_r(NULL, " ", &saveptr3);
        if (body_token) {
          state->players[i].body[j].y = atoi(body_token);
          body_token = strtok_r(NULL, " ", &saveptr3);
        }
      }
    }
  }

  token = strtok_r(NULL, "|", &saveptr1);
  if (token) {
    char *stat_token = strtok_r(token, " ", &saveptr2);
    state->active_players = stat_token ? atoi(stat_token) : 0;
    stat_token = strtok_r(NULL, " ", &saveptr2);
    state->total_players = stat_token ? atoi(stat_token) : 0;
  }

  return 0;
}
