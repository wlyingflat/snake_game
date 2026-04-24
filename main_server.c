/**
 * main_server.c - 贪吃蛇游戏主服务器（网关推送版）
 *
 * 功能：处理用户认证、房间创建/加入，通过管道通知网关推送房间列表更新。
 */

#include "config.h"
#include "debug.h"
#include "network.h"
#include "room_manager.h"
#include "shm_manager.h"
#include "threadpool.h"
#include "user_manager.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/wait.h>
#include <unistd.h>

/* 全局变量 */
static volatile int running = 1; /* 运行标志 */
static ThreadPool *thread_pool = NULL;
static SharedMemory *shm = NULL;
static UserManager *user_manager = NULL;
static RoomManager *room_manager = NULL;
static int epoll_fd = -1;
static int server_fd = -1;
static int gateway_pipe_fd = -1; /* 写入网关的管道 */
static int gateway_port = GATEWAY_DEFAULT_PORT;
static pid_t gateway_pid = 0;

/* 函数声明 */
static void cleanup(void);
static void signal_handler(int sig);
static void sigchld_handler(int sig);
static void notify_gateway_refresh(void);

/* 通知网关刷新房间列表 */
static void notify_gateway_refresh(void) {
  if (gateway_pipe_fd >= 0) {
    ssize_t w = write(gateway_pipe_fd, "REFRESH", 7);
    if (w < 0 && errno != EPIPE)
      ERROR("Failed to write to gateway pipe: %s", strerror(errno));
    else if (w < 0) /* EPIPE */
      ERROR("Gateway pipe broken, gateway may have crashed");
    else
      DBG("Sent REFRESH to gateway");
  }
}

/* 处理客户端请求（线程池任务） */
static void handle_client_request(void *arg) {
  ClientRequest *req = (ClientRequest *)arg;
  int fd = req->socket_fd;
  char *buffer = req->buffer;

  char cmd[32] = {0}, arg1[USERNAME_LEN] = {0}, arg2[USERNAME_LEN] = {0};
  if (!parse_command(buffer, cmd, arg1, arg2)) {
    send_message(fd, "ERROR Invalid command format");
    goto cleanup;
  }

  /* 命令分发 */
  if (strcmp(cmd, CMD_REGISTER) == 0) {
    if (user_register(user_manager, arg1, arg2)) {
      char resp[BUFFER_SIZE];
      snprintf(resp, sizeof(resp),
               "OK Registration successful\nGATEWAY 127.0.0.1 %d",
               gateway_port);
      send_message(fd, resp);
    } else
      send_message(fd, "ERROR Registration failed");
  } else if (strcmp(cmd, CMD_LOGIN) == 0) {
    if (user_login(user_manager, arg1, arg2)) {
      char resp[BUFFER_SIZE];
      snprintf(resp, sizeof(resp), "OK Login successful\nGATEWAY 127.0.0.1 %d",
               gateway_port);
      send_message(fd, resp);
    } else
      send_message(fd, "ERROR Login failed");
  } else if (strcmp(cmd, CMD_CREATE) == 0) {
    int room_id = atoi(arg1);
    if (room_create(room_manager, room_id, arg2)) {
      RoomInfo *room = shm_get_room(shm, room_id);
      char resp[64];
      snprintf(resp, sizeof(resp), "REDIRECT %d %d", room->port, room_id);
      send_message(fd, resp);
      notify_gateway_refresh();
    } else
      send_message(fd, "ERROR Cannot create room");
  } else if (strcmp(cmd, CMD_JOIN) == 0) {
    int room_id = atoi(arg1);
    if (room_join(room_manager, room_id, arg2)) {
      RoomInfo *room = shm_get_room(shm, room_id);
      char resp[64];
      snprintf(resp, sizeof(resp), "REDIRECT %d %d", room->port, room_id);
      send_message(fd, resp);
      notify_gateway_refresh();
    } else
      send_message(fd, "ERROR Cannot join room");
  } else if (strcmp(cmd, CMD_LOGOUT) == 0) {
    if (user_logout(user_manager, arg1))
      send_message(fd, "OK Logout successful");
    else
      send_message(fd, "ERROR Logout failed");
  } else if (strcmp(cmd, CMD_ROOM_LIST) == 0) {
    char *room_list_str = room_list(room_manager);
    send_message(fd, room_list_str);
    free(room_list_str);
  } else
    send_message(fd, "ERROR Unknown command");

cleanup:
  close(fd);
  free(req);
}

/* 清理资源 */
static void cleanup(void) {
  INFO("Cleaning up resources...");
  running = 0;

  if (gateway_pid > 0) {
    kill(gateway_pid, SIGTERM);
    waitpid(gateway_pid, NULL, 0);
  }
  if (gateway_pipe_fd >= 0)
    close(gateway_pipe_fd);
  if (epoll_fd >= 0)
    close(epoll_fd);
  if (server_fd >= 0)
    close(server_fd);
  if (thread_pool)
    threadpool_destroy(thread_pool);
  if (room_manager)
    room_manager_destroy(room_manager);
  if (user_manager)
    user_manager_destroy(user_manager);
  if (shm)
    shm_detach(shm);
  INFO("Cleanup complete");
}

/* SIGCHLD 处理 */
static void sigchld_handler(int sig) {
  (void)sig; // 消除未使用参数警告
  int saved_errno = errno;
  while (waitpid(-1, NULL, WNOHANG) > 0)
    ;
  errno = saved_errno;
}

/* 信号处理 */
static void signal_handler(int sig) {
  INFO("Received signal %d, shutting down...", sig);
  running = 0;
}

/* 主函数 */
int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "Usage: %s <port> [gateway_port]\n", argv[0]);
    return EXIT_FAILURE;
  }

  int port = atoi(argv[1]);
  if (argc >= 3)
    gateway_port = atoi(argv[2]);

  INFO("Starting main server on port %d, gateway port %d", port, gateway_port);

  /* 信号处理 */
  signal(SIGINT, signal_handler);
  signal(SIGTERM, signal_handler);
  signal(SIGCHLD, sigchld_handler);
  signal(SIGPIPE, SIG_IGN);

  /* 初始化各模块 */
  if (!(shm = shm_create()))
    FATAL("Failed to initialize shared memory");
  if (!(user_manager = user_manager_create("users.txt", shm)))
    FATAL("Failed to initialize user manager");
  if (!(room_manager = room_manager_create(shm, BASE_ROOM_PORT)))
    FATAL("Failed to initialize room manager");
  if (!(thread_pool = threadpool_create(THREAD_POOL_SIZE, THREAD_QUEUE_SIZE)))
    FATAL("Failed to create thread pool");

  /* 创建管道并与网关进程通信 */
  int pipefd[2];
  if (pipe(pipefd) < 0)
    FATAL("Failed to create pipe for gateway");
  pid_t pid = fork();
  if (pid < 0)
    FATAL("Failed to fork gateway");
  if (pid == 0) { /* 子进程：网关 */
    close(pipefd[1]);
    char port_str[16], pipe_str[16];
    snprintf(port_str, sizeof(port_str), "%d", gateway_port);
    snprintf(pipe_str, sizeof(pipe_str), "%d", pipefd[0]);
    execl("./gateway", "./gateway", port_str, pipe_str, NULL);
    ERROR("execl failed");
    exit(EXIT_FAILURE);
  }
  close(pipefd[0]);
  gateway_pipe_fd = pipefd[1];
  gateway_pid = pid;
  INFO("Gateway started on port %d, PID=%d", gateway_port, pid);

  /* 创建服务器 socket */
  if ((server_fd = server_create(port)) < 0)
    FATAL("Failed to create server socket");

  /* 创建 epoll 实例 */
  epoll_fd = epoll_create1(0);
  if (epoll_fd < 0)
    FATAL("Failed to create epoll: %s", strerror(errno));
  struct epoll_event ev;
  ev.events = EPOLLIN;
  ev.data.fd = server_fd;
  if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, server_fd, &ev) < 0)
    FATAL("Failed to add server fd to epoll: %s", strerror(errno));

  INFO("Main server is ready and listening on port %d", port);

  struct epoll_event events[MAX_EVENTS];
  while (running) {
    int n = epoll_wait(epoll_fd, events, MAX_EVENTS, -1);
    if (n < 0) {
      if (errno == EINTR)
        continue;
      ERROR("epoll_wait error: %s", strerror(errno));
      break;
    }

    for (int i = 0; i < n; i++) {
      if (events[i].data.fd == server_fd) {
        /* 新连接 */
        struct sockaddr_in client_addr;
        socklen_t addr_len = sizeof(client_addr);
        int client_fd = server_accept(server_fd, &client_addr);
        if (client_fd < 0)
          continue;
        (void)addr_len; // 消除未使用变量警告
        server_set_nonblocking(client_fd);
        ev.events = EPOLLIN | EPOLLET;
        ev.data.fd = client_fd;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, client_fd, &ev) < 0) {
          ERROR("Failed to add client to epoll: %s", strerror(errno));
          close(client_fd);
        }
      } else {
        int client_fd = events[i].data.fd;
        char buffer[BUFFER_SIZE] = {0};

        int r = receive_message(client_fd, buffer, sizeof(buffer),
                                RECV_TIMEOUT * 1000);
        if (r <= 0) {
          if (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
            ERROR("Receive error on fd=%d: %s", client_fd, strerror(errno));
          epoll_ctl(epoll_fd, EPOLL_CTL_DEL, client_fd, NULL);
          close(client_fd);
          continue;
        }

        /* 从 epoll 中移除该 fd，后续交给线程池处理 */
        epoll_ctl(epoll_fd, EPOLL_CTL_DEL, client_fd, NULL);

        ClientRequest *req = malloc(sizeof(ClientRequest));
        if (!req) {
          ERROR("Failed to allocate request");
          close(client_fd);
          continue;
        }
        req->socket_fd = client_fd;
        strncpy(req->buffer, buffer, sizeof(req->buffer) - 1);
        req->buffer[sizeof(req->buffer) - 1] = '\0';

        if (threadpool_add(thread_pool, handle_client_request, req) != 0) {
          ERROR("Thread pool queue full, dropping request");
          free(req);
          close(client_fd);
        }
      }
    }
  }

  cleanup();
  return EXIT_SUCCESS;
}
