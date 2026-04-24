/**
 * network.h - 网络通信接口
 *
 * 功能：声明连接管理、数据收发、协议解析、游戏状态序列化等函数。
 */

#ifndef NETWORK_H
#define NETWORK_H

#include "common.h"
#include <netinet/in.h>
#include <sys/socket.h>

// 网络连接管理
typedef struct {
  int socket_fd;
  struct sockaddr_in address;
  int is_connected;
} Connection;

// 连接管理
Connection *connection_create();
int connection_connect(Connection *conn, const char *ip, int port);
void connection_close(Connection *conn);
void connection_destroy(Connection *conn);

// 服务器端
int server_create(int port);
int server_accept(int server_fd, struct sockaddr_in *client_addr);
void server_set_nonblocking(int fd);

// 数据收发
int send_message(int fd, const char *message);
int receive_message(int fd, char *buffer, size_t size, int timeout_ms);

// 协议处理
int parse_command(const char *buffer, char *cmd, char *arg1, char *arg2);
char *build_response(const char *type, const char *arg1, const char *arg2);

// 游戏状态序列化/反序列化
char *serialize_game_state(GameStateData *state);
int deserialize_game_state(const char *buffer, GameStateData *state);

#endif // NETWORK_H
