/**
 * client.c - 贪吃蛇游戏客户端（适配 Java 服务器 JSON 协议）
 *
 * 功能：提供基于 ncurses
 * 的终端界面，处理用户交互，登录后连接网关接收实时房间列表更新。 适配 Java
 * 服务器：游戏状态使用 JSON 格式（{"type":"STATE","data":{...}}）。 编译：gcc
 * -o client client.c cJSON.c -lncurses -lm
 */

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <ncurses.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <time.h>
#include <unistd.h>

#include "cJSON.h"

/* ---------------------------- 常量定义（与服务器保持一致）
 * ---------------------------- */
#define MAP_WIDTH 40
#define MAP_HEIGHT 20
#define MAX_SNAKE_LENGTH 63
#define MAX_PLAYERS_PER_ROOM 8
#define OBSTACLE_COUNT 15
#define MAX_ROOMS 8
#define USERNAME_LEN 32
#define HEARTBEAT_INTERVAL 30
#define HEARTBEAT_TIMEOUT 60
#define MAX_RETRY_ATTEMPTS 5
#define RETRY_DELAY_MS 200
#define BUFFER_SIZE 32768       /* 发送缓冲区大小 */
#define RECV_BUFFER_SIZE 65536  /* 接收缓冲区大小 */
#define RECV_TIMEOUT_MS 5000    /* 接收超时（毫秒） */
#define SELECT_TIMEOUT_US 10000 /* select 超时微秒 */

/* 协议相关常量（与 Java 服务器保持一致） */
#define CMD_REGISTER "REG"
#define CMD_LOGIN "LOGIN"
#define CMD_CREATE "CREATE"
#define CMD_JOIN "JOIN"
#define CMD_LOGOUT "LOGOUT"
#define CMD_ROOM_LIST "ROOM_LIST"
#define RESP_OK "OK"
#define RESP_ERROR "ERROR"
#define RESP_REDIRECT "REDIRECT"
#define PROTOCOL_PING "PING"
#define PROTOCOL_PONG "PONG"
#define PROTOCOL_USER "USER"
#define PROTOCOL_QUIT "QUIT"
#define PROTOCOL_PLAYER "PLAYER"
#define PROTOCOL_WELCOME "WELCOME"
#define PROTOCOL_YOU_DIED "YOU DIED"
#define ROOM_LIST_UPDATE_PREFIX "ROOM_LIST_UPDATE|"

/* ---------------------------- 界面常量 ---------------------------- */
#define STATUS_BAR_Y (LINES - 1)
#define MENU_START_Y 5
#define MAP_RENDER_START_Y 3
#define INFO_PANEL_X_OFFSET (MAP_WIDTH + 5)

/* 颜色对索引 */
enum {
  COLOR_PAIR_NORMAL = 1,
  COLOR_PAIR_TITLE,
  COLOR_PAIR_MENU,
  COLOR_PAIR_INPUT,
  COLOR_PAIR_PLAYER,
  COLOR_PAIR_FOOD,
  COLOR_PAIR_OBSTACLE,
  COLOR_PAIR_WALL,
  COLOR_PAIR_STATUS,
  COLOR_PAIR_BORDER
};

/* 焦点区域 */
typedef enum { FOCUS_ROOM_LIST, FOCUS_CREATE, FOCUS_LOGOUT } FocusType;
typedef enum {
  AUTH_FOCUS_USERNAME,
  AUTH_FOCUS_PASSWORD,
  AUTH_FOCUS_SUBMIT
} AuthFocus;
typedef enum {
  STATE_AUTH,
  STATE_ROOM_LIST,
  STATE_IN_ROOM,
  STATE_GAME_OVER,
  STATE_EXIT
} ClientState;
typedef enum { OP_CREATE, OP_JOIN } RoomOp;

/* ---------------------------- 数据结构定义 ---------------------------- */
typedef struct {
  int x, y;
} Position;

typedef struct {
  char name[USERNAME_LEN];
  Position head;
  Position body[MAX_SNAKE_LENGTH];
  int length;
  int direction; // 0:UP,1:DOWN,2:LEFT,3:RIGHT
  int score;
  int is_dead;
  int is_you;
} PlayerInfo;

typedef struct {
  int room_id;
  Position food;
  Position obstacles[OBSTACLE_COUNT];
  int obstacle_count;
  PlayerInfo players[MAX_PLAYERS_PER_ROOM];
  int player_count;
  int active_players;
  int total_players;
} GameStateData;

/* 连接结构体（简化版） */
typedef struct {
  int socket_fd;
} Connection;

/* 客户端上下文 */
typedef struct {
  ClientState state;
  char username[USERNAME_LEN];
  char password[USERNAME_LEN];
  int is_logged_in;
  Connection *room_conn;
  Connection *gateway_conn;
  char server_ip[64];
  int server_port;
  char gateway_ip[64];
  int gateway_port;
} ClientContext;

/* 房间条目结构 */
typedef struct {
  int id;
  char line[256];
} RoomEntry;

/* 游戏画面缓存 */
typedef struct {
  char name[USERNAME_LEN];
  Position body[MAX_SNAKE_LENGTH];
  int length;
  int is_dead;
} SnakeCache;

static SnakeCache prev_snakes[MAX_PLAYERS_PER_ROOM];
static int prev_snake_count = 0;
static Position prev_food = {-1, -1};
static int static_map_drawn = 0;

/* ---------------------------- 函数声明 ---------------------------- */
static int recv_line(int fd, char *buffer, size_t size, int timeout_ms);
static Connection *connection_create(void);
static int connection_connect(Connection *conn, const char *ip, int port);
static void connection_destroy(Connection *conn);
static int send_message(int fd, const char *message);
static int read_int_input_ncurses(int min, int max);
static void show_message_centered(const char *msg);
static void draw_border(void);
static void init_color_pairs(void);
static void draw_status_bar(ClientContext *ctx);
static void draw_menu_options(const char *options[], int n_options,
                              int selected, int start_y);
static int send_request_to_main_server(ClientContext *ctx, char *response_buf,
                                       int buf_size, const char *fmt, ...);
static int connect_and_send_auth(ClientContext *ctx, int is_register);
static int connect_to_room_server(ClientContext *ctx, int port);
static int send_room_operation(ClientContext *ctx, RoomOp op, int room_id);
static int show_auth_menu_ncurses(ClientContext *ctx);
static int auth_input_form(ClientContext *ctx, int is_register);
static int show_room_menu_with_gateway(ClientContext *ctx);
static int game_loop_ncurses(ClientContext *ctx);
static void render_game_state(const GameStateData *state);
static void draw_static_map(const GameStateData *state);
static void parse_room_list(const char *list, RoomEntry *rooms, int *count);
static int deserialize_game_state_json(const char *json, GameStateData *state);
static void init_client_context(ClientContext *ctx, const char *ip, int port);
static void cleanup_client_context(ClientContext *ctx);

/* ---------------------------- 网络辅助函数 ---------------------------- */
static Connection *connection_create(void) {
  Connection *conn = malloc(sizeof(Connection));
  if (conn) {
    conn->socket_fd = -1;
  }
  return conn;
}

static int connection_connect(Connection *conn, const char *ip, int port) {
  if (!conn)
    return -1;
  conn->socket_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (conn->socket_fd < 0)
    return -1;

  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  if (inet_pton(AF_INET, ip, &addr.sin_addr) <= 0) {
    close(conn->socket_fd);
    conn->socket_fd = -1;
    return -1;
  }
  if (connect(conn->socket_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
    close(conn->socket_fd);
    conn->socket_fd = -1;
    return -1;
  }
  return 0;
}

static void connection_destroy(Connection *conn) {
  if (!conn)
    return;
  if (conn->socket_fd >= 0)
    close(conn->socket_fd);
  free(conn);
}

static int send_message(int fd, const char *message) {
  if (fd < 0 || !message)
    return -1;
  size_t len = strlen(message);
  char *buf = malloc(len + 2);
  if (!buf)
    return -1;
  snprintf(buf, len + 2, "%s\n", message);
  ssize_t sent = send(fd, buf, len + 1, 0);
  free(buf);
  return (sent >= 0) ? 0 : -1;
}

/**
 * 从 socket 读取一行（以 \n 结尾），支持超时（毫秒）
 * 返回读取的字节数（不含换行），超时或错误返回 -1，连接关闭返回 0
 */
static int recv_line(int fd, char *buffer, size_t size, int timeout_ms) {
  if (fd < 0 || !buffer || size == 0)
    return -1;
  size_t pos = 0;
  struct timeval tv;
  fd_set fds;
  int ret;
  char ch;

  while (pos < size - 1) {
    FD_ZERO(&fds);
    FD_SET(fd, &fds);
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    ret = select(fd + 1, &fds, NULL, NULL, &tv);
    if (ret < 0) {
      if (errno == EINTR)
        continue;
      return -1;
    } else if (ret == 0) {
      return -1; // timeout
    }
    ssize_t n = recv(fd, &ch, 1, 0);
    if (n == 0) {
      return 0; // connection closed
    } else if (n < 0) {
      return -1;
    }
    if (ch == '\n') {
      buffer[pos] = '\0';
      return (int)pos;
    }
    buffer[pos++] = ch;
  }
  buffer[pos] = '\0';
  return (int)pos;
}

/* ---------------------------- 辅助函数（界面美化）
 * ---------------------------- */
static void init_color_pairs(void) {
  if (has_colors()) {
    start_color();
    init_pair(COLOR_PAIR_NORMAL, COLOR_WHITE, COLOR_BLACK);
    init_pair(COLOR_PAIR_TITLE, COLOR_YELLOW, COLOR_BLACK);
    init_pair(COLOR_PAIR_MENU, COLOR_CYAN, COLOR_BLACK);
    init_pair(COLOR_PAIR_INPUT, COLOR_GREEN, COLOR_BLACK);
    init_pair(COLOR_PAIR_PLAYER, COLOR_GREEN, COLOR_BLACK);
    init_pair(COLOR_PAIR_FOOD, COLOR_RED, COLOR_BLACK);
    init_pair(COLOR_PAIR_OBSTACLE, COLOR_YELLOW, COLOR_BLACK);
    init_pair(COLOR_PAIR_WALL, COLOR_WHITE, COLOR_BLACK);
    init_pair(COLOR_PAIR_STATUS, COLOR_BLACK, COLOR_WHITE);
    init_pair(COLOR_PAIR_BORDER, COLOR_CYAN, COLOR_BLACK);
  }
}

static void draw_border(void) {
  attron(COLOR_PAIR(COLOR_PAIR_BORDER));
  border(0, 0, 0, 0, 0, 0, 0, 0);
  attroff(COLOR_PAIR(COLOR_PAIR_BORDER));
}

static void draw_status_bar(ClientContext *ctx) {
  attron(COLOR_PAIR(COLOR_PAIR_STATUS));
  mvhline(STATUS_BAR_Y, 0, ' ', COLS);
  char status[COLS];
  const char *state_str = "";
  switch (ctx->state) {
  case STATE_AUTH:
    state_str = "Authentication";
    break;
  case STATE_ROOM_LIST:
    state_str = "Room List";
    break;
  case STATE_IN_ROOM:
    state_str = "In Game";
    break;
  case STATE_GAME_OVER:
    state_str = "Game Over";
    break;
  default:
    state_str = "Unknown";
    break;
  }
  snprintf(status, sizeof(status), " User: %s | State: %s | Server: %s:%d ",
           ctx->is_logged_in ? ctx->username : "Not logged in", state_str,
           ctx->server_ip, ctx->server_port);
  mvprintw(STATUS_BAR_Y, 1, "%-*s", COLS - 2, status);
  attroff(COLOR_PAIR(COLOR_PAIR_STATUS));
  refresh();
}

static void draw_menu_options(const char *options[], int n_options,
                              int selected, int start_y) {
  int max_len = 0;
  for (int i = 0; i < n_options; i++) {
    int len = strlen(options[i]);
    if (len > max_len)
      max_len = len;
  }
  int option_width = max_len + 4;
  int x = (COLS - option_width) / 2;
  for (int i = 0; i < n_options; i++) {
    if (i == selected) {
      attron(COLOR_PAIR(COLOR_PAIR_MENU) | A_REVERSE);
      mvprintw(start_y + i * 2, x, "  %-*s  ", max_len, options[i]);
      attroff(COLOR_PAIR(COLOR_PAIR_MENU) | A_REVERSE);
    } else {
      attron(COLOR_PAIR(COLOR_PAIR_MENU));
      mvprintw(start_y + i * 2, x, "  %-*s  ", max_len, options[i]);
      attroff(COLOR_PAIR(COLOR_PAIR_MENU));
    }
  }
}

static int read_int_input_ncurses(int min, int max) {
  char input[32];
  attron(COLOR_PAIR(COLOR_PAIR_INPUT));
  mvprintw(LINES - 3, 2, "Enter number (%d-%d): ", min, max);
  clrtoeol();
  echo();
  curs_set(1);
  mvgetnstr(LINES - 3, 22, input, sizeof(input) - 1);
  noecho();
  curs_set(0);
  attroff(COLOR_PAIR(COLOR_PAIR_INPUT));
  move(LINES - 3, 0);
  clrtoeol();
  refresh();
  int value = atoi(input);
  return (value >= min && value <= max) ? value : -1;
}

static void show_message_centered(const char *msg) {
  clear();
  int box_width = strlen(msg) + 4;
  if (box_width > COLS)
    box_width = COLS - 4;
  int start_x = (COLS - box_width) / 2;
  int start_y = (LINES - 5) / 2;
  attron(COLOR_PAIR(COLOR_PAIR_BORDER));
  for (int i = 0; i < 5; i++)
    mvhline(start_y + i, start_x, ' ', box_width);
  attroff(COLOR_PAIR(COLOR_PAIR_BORDER));
  attron(COLOR_PAIR(COLOR_PAIR_TITLE));
  mvprintw(start_y + 2, start_x + 2, "%s", msg);
  attroff(COLOR_PAIR(COLOR_PAIR_TITLE));
  mvprintw(start_y + 4, start_x + 2, "Press any key");
  refresh();
  getch();
}

/* ---------------------------- 网络请求辅助 ---------------------------- */
static int send_request_to_main_server(ClientContext *ctx, char *response_buf,
                                       int buf_size, const char *fmt, ...) {
  Connection *conn = connection_create();
  if (!conn)
    return -1;
  if (connection_connect(conn, ctx->server_ip, ctx->server_port) < 0) {
    connection_destroy(conn);
    return -1;
  }
  va_list args;
  va_start(args, fmt);
  char request[BUFFER_SIZE];
  vsnprintf(request, sizeof(request), fmt, args);
  va_end(args);
  send_message(conn->socket_fd, request);

  char response[BUFFER_SIZE];
  int n =
      recv_line(conn->socket_fd, response, sizeof(response), RECV_TIMEOUT_MS);
  if (n <= 0) {
    connection_destroy(conn);
    return -1;
  }
  if (response_buf && buf_size > 0) {
    strncpy(response_buf, response, buf_size - 1);
    response_buf[buf_size - 1] = '\0';
  }
  connection_destroy(conn);
  return 0;
}

/* ---------------------------- 认证相关 ---------------------------- */
static int connect_and_send_auth(ClientContext *ctx, int is_register) {
  Connection *conn = connection_create();
  if (!conn) {
    show_message_centered("Failed to create connection");
    return STATE_AUTH;
  }

  // 连接主服务器
  if (connection_connect(conn, ctx->server_ip, ctx->server_port) < 0) {
    show_message_centered("Failed to connect to main server");
    connection_destroy(conn);
    return STATE_AUTH;
  }

  // 发送注册/登录命令
  const char *cmd = is_register ? CMD_REGISTER : CMD_LOGIN;
  char request[BUFFER_SIZE];
  snprintf(request, sizeof(request), "%s %s %s", cmd, ctx->username,
           ctx->password);
  send_message(conn->socket_fd, request);

  // 读取第一行响应（如 "OK Registration successful"）
  char line1[BUFFER_SIZE];
  int n1 = recv_line(conn->socket_fd, line1, sizeof(line1), RECV_TIMEOUT_MS);
  if (n1 <= 0) {
    show_message_centered("No response from server");
    connection_destroy(conn);
    return STATE_AUTH;
  }

  // 检查第一行是否成功
  if (strncmp(line1, "OK", 2) != 0) {
    show_message_centered(line1);
    connection_destroy(conn);
    return STATE_AUTH;
  }

  // 读取第二行（网关信息），超时设为 500ms，因为服务器会立即发送
  char line2[BUFFER_SIZE];
  int n2 = recv_line(conn->socket_fd, line2, sizeof(line2), 500);
  if (n2 <= 0 || sscanf(line2, "GATEWAY %63s %d", ctx->gateway_ip,
                        &ctx->gateway_port) != 2) {
    show_message_centered("Invalid gateway info from server");
    connection_destroy(conn);
    return STATE_AUTH;
  }

  connection_destroy(conn); // 关闭主服务器连接

  // 连接网关
  ctx->gateway_conn = connection_create();
  if (!ctx->gateway_conn ||
      connection_connect(ctx->gateway_conn, ctx->gateway_ip,
                         ctx->gateway_port) < 0) {
    show_message_centered("Failed to connect to gateway");
    if (ctx->gateway_conn)
      connection_destroy(ctx->gateway_conn);
    ctx->gateway_conn = NULL;
    return STATE_AUTH;
  }

  // 发送用户标识
  char user_msg[USERNAME_LEN + 16];
  snprintf(user_msg, sizeof(user_msg), "USER %s", ctx->username);
  send_message(ctx->gateway_conn->socket_fd, user_msg);
  ctx->is_logged_in = 1;

  return STATE_ROOM_LIST;
}

static int input_string(int y, int x, char *buffer, int max_len, int echo) {
  int pos = 0, ch;
  buffer[0] = '\0';
  move(y, x);
  curs_set(1);
  while (1) {
    ch = getch();
    if (ch == '\n' || ch == KEY_ENTER)
      break;
    else if (ch == KEY_BACKSPACE || ch == 127 || ch == 8) {
      if (pos > 0) {
        pos--;
        move(y, x + pos);
        addch(' ');
        move(y, x + pos);
        refresh();
      }
    } else if (isprint(ch) && pos < max_len - 1) {
      buffer[pos++] = ch;
      if (echo)
        addch(ch);
      else
        addch('*');
      refresh();
    }
  }
  buffer[pos] = '\0';
  curs_set(0);
  return pos;
}

static int auth_input_form(ClientContext *ctx, int is_register) {
  ctx->username[0] = '\0';
  ctx->password[0] = '\0';
  clear();
  draw_border();
  draw_status_bar(ctx);
  const char *title = is_register ? "Register New User" : "Login";
  attron(COLOR_PAIR(COLOR_PAIR_TITLE) | A_BOLD);
  mvprintw(3, (COLS - strlen(title)) / 2, "%s", title);
  attroff(COLOR_PAIR(COLOR_PAIR_TITLE) | A_BOLD);
  int start_y = 7, label_x = COLS / 2 - 15, input_x = label_x + 12;
  mvprintw(start_y, label_x, "Username:");
  mvprintw(start_y + 2, label_x, "Password:");
  int btn_y = start_y + 5, btn_x = COLS / 2 - 5;
  mvprintw(btn_y, btn_x, "[ Submit ]");
  AuthFocus focus = AUTH_FOCUS_USERNAME;
  int redraw = 1;
  while (1) {
    if (redraw) {
      char password_mask[USERNAME_LEN];
      int pass_len = strlen(ctx->password);
      for (int i = 0; i < pass_len; i++)
        password_mask[i] = '*';
      password_mask[pass_len] = '\0';
      attron(COLOR_PAIR(COLOR_PAIR_INPUT));
      mvprintw(start_y, input_x, "%-*s", USERNAME_LEN, ctx->username);
      mvprintw(start_y + 2, input_x, "%-*s", USERNAME_LEN, password_mask);
      attroff(COLOR_PAIR(COLOR_PAIR_INPUT));
      mvprintw(btn_y, btn_x, "[ Submit ]");
      if (focus == AUTH_FOCUS_USERNAME) {
        attron(COLOR_PAIR(COLOR_PAIR_INPUT) | A_REVERSE);
        mvprintw(start_y, input_x, "%-*s", USERNAME_LEN, ctx->username);
        attroff(COLOR_PAIR(COLOR_PAIR_INPUT) | A_REVERSE);
      } else if (focus == AUTH_FOCUS_PASSWORD) {
        attron(COLOR_PAIR(COLOR_PAIR_INPUT) | A_REVERSE);
        mvprintw(start_y + 2, input_x, "%-*s", USERNAME_LEN, password_mask);
        attroff(COLOR_PAIR(COLOR_PAIR_INPUT) | A_REVERSE);
      } else if (focus == AUTH_FOCUS_SUBMIT) {
        attron(A_REVERSE);
        mvprintw(btn_y, btn_x, "[ Submit ]");
        attroff(A_REVERSE);
      }
      mvprintw(LINES - 4, 2,
               "↑/W or ↓/S: move focus, Enter: edit/confirm, ESC: cancel");
      refresh();
      redraw = 0;
    }
    int ch = getch();
    switch (ch) {
    case KEY_UP:
    case 'w':
    case 'W':
      if (focus == AUTH_FOCUS_PASSWORD)
        focus = AUTH_FOCUS_USERNAME;
      else if (focus == AUTH_FOCUS_SUBMIT)
        focus = AUTH_FOCUS_PASSWORD;
      redraw = 1;
      break;
    case KEY_DOWN:
    case 's':
    case 'S':
      if (focus == AUTH_FOCUS_USERNAME)
        focus = AUTH_FOCUS_PASSWORD;
      else if (focus == AUTH_FOCUS_PASSWORD)
        focus = AUTH_FOCUS_SUBMIT;
      redraw = 1;
      break;
    case '\n':
    case KEY_ENTER:
      if (focus == AUTH_FOCUS_USERNAME) {
        input_string(start_y, input_x, ctx->username, USERNAME_LEN, 1);
        redraw = 1;
      } else if (focus == AUTH_FOCUS_PASSWORD) {
        input_string(start_y + 2, input_x, ctx->password, USERNAME_LEN, 0);
        redraw = 1;
      } else if (focus == AUTH_FOCUS_SUBMIT) {
        if (strlen(ctx->username) == 0 ||
            strlen(ctx->username) >= USERNAME_LEN - 1) {
          show_message_centered("Invalid username length (1-31 characters)");
          return STATE_AUTH;
        }
        if (strlen(ctx->password) == 0 ||
            strlen(ctx->password) >= USERNAME_LEN - 1) {
          show_message_centered("Invalid password length (1-31 characters)");
          return STATE_AUTH;
        }
        clear();
        mvprintw(LINES / 2, (COLS - 22) / 2, "Connecting to server...");
        refresh();
        int next_state = connect_and_send_auth(ctx, is_register);
        if (next_state == STATE_ROOM_LIST || next_state == STATE_AUTH)
          return next_state;
        redraw = 1;
      }
      break;
    case 27:
      return STATE_AUTH;
    default:
      break;
    }
  }
}

static int show_auth_menu_ncurses(ClientContext *ctx) {
  const char *options[] = {"Register", "Login", "Exit"};
  int selected = 0;
  while (1) {
    clear();
    draw_border();
    draw_status_bar(ctx);
    attron(COLOR_PAIR(COLOR_PAIR_TITLE) | A_BOLD);
    mvprintw(2, (COLS - strlen("=== Snake Game ===")) / 2,
             "=== Snake Game ===");
    attroff(COLOR_PAIR(COLOR_PAIR_TITLE) | A_BOLD);
    draw_menu_options(options, 3, selected, MENU_START_Y);
    mvprintw(LINES - 4, 2, "Use Up/Down arrows to navigate, Enter to select.");
    refresh();
    int ch = getch();
    switch (ch) {
    case KEY_UP:
      selected = (selected - 1 + 3) % 3;
      break;
    case KEY_DOWN:
      selected = (selected + 1) % 3;
      break;
    case '\n':
    case KEY_ENTER:
      if (selected == 2)
        return STATE_EXIT;
      return auth_input_form(ctx, selected == 0);
    default:
      break;
    }
  }
}

/* ---------------------------- 房间列表相关 ---------------------------- */
static void parse_room_list(const char *list, RoomEntry *rooms, int *count) {
  *count = 0;
  if (!list)
    return;
  char *p = (char *)list;
  char *nl;
  while ((nl = strchr(p, '\n')) != NULL && *count < MAX_ROOMS) {
    int len = nl - p;
    if (len > 0 && len < 256) {
      char line[256];
      strncpy(line, p, len);
      line[len] = '\0';
      int id;
      if (sscanf(line, "%d", &id) == 1) {
        rooms[*count].id = id;
        strcpy(rooms[*count].line, line);
        (*count)++;
      }
    }
    p = nl + 1;
  }
  if (*p && *count < MAX_ROOMS) {
    int id;
    if (sscanf(p, "%d", &id) == 1) {
      rooms[*count].id = id;
      strncpy(rooms[*count].line, p, 255);
      rooms[*count].line[255] = '\0';
      (*count)++;
    }
  }
}

static int compute_room_list_max_width(const RoomEntry *rooms, int room_count) {
  const char *header1 = "ID  Status  Players  Port    Created";
  const char *header2 = "--- ------- ------- ------- ----------";
  int max_width = strlen(header1);
  int len2 = strlen(header2);
  if (len2 > max_width)
    max_width = len2;
  for (int i = 0; i < room_count; i++) {
    int len = strlen(rooms[i].line);
    if (len > max_width)
      max_width = len;
  }
  if (room_count == 0) {
    int no_rooms_len = strlen("No active rooms.");
    if (no_rooms_len > max_width)
      max_width = no_rooms_len;
  }
  return max_width;
}

static void draw_room_list_menu(const RoomEntry *rooms, int room_count,
                                int selected_room, ClientContext *ctx,
                                FocusType focus) {
  clear();
  draw_border();
  draw_status_bar(ctx);
  attron(COLOR_PAIR(COLOR_PAIR_TITLE) | A_BOLD);
  mvprintw(3, (COLS - 15) / 2, "=== Room List ===");
  attroff(COLOR_PAIR(COLOR_PAIR_TITLE) | A_BOLD);
  int max_width = compute_room_list_max_width(rooms, room_count);
  int start_x = (COLS - max_width) / 2;
  if (start_x < 0)
    start_x = 0;
  mvprintw(4, start_x, "%-*s", max_width,
           "ID  Status  Players  Port    Created");
  mvprintw(5, start_x, "%-*s", max_width,
           "--- ------- ------- ------- ----------");
  if (room_count > 0) {
    for (int i = 0; i < room_count; i++) {
      int y = 6 + i;
      if (y >= LINES - 6)
        break;
      if (i == selected_room) {
        attron(A_REVERSE);
        mvprintw(y, start_x, "%-*s", max_width, rooms[i].line);
        attroff(A_REVERSE);
      } else {
        mvprintw(y, start_x, "%-*s", max_width, rooms[i].line);
      }
    }
  } else {
    mvprintw(6, start_x, "%-*s", max_width, "No active rooms.");
  }
  int btn_y = LINES - 4;
  int btn1_len = 14, btn2_len = 10, spacing = 4;
  int total_width = btn1_len + spacing + btn2_len;
  int btn_start_x = (COLS - total_width) / 2;
  attron(COLOR_PAIR(COLOR_PAIR_MENU));
  if (focus == FOCUS_CREATE)
    attron(A_REVERSE);
  mvprintw(btn_y, btn_start_x, "[ Create Room ]");
  if (focus == FOCUS_CREATE)
    attroff(A_REVERSE);
  attroff(COLOR_PAIR(COLOR_PAIR_MENU));
  attron(COLOR_PAIR(COLOR_PAIR_MENU));
  if (focus == FOCUS_LOGOUT)
    attron(A_REVERSE);
  mvprintw(btn_y, btn_start_x + btn1_len + spacing, "[ Logout ]");
  if (focus == FOCUS_LOGOUT)
    attroff(A_REVERSE);
  attroff(COLOR_PAIR(COLOR_PAIR_MENU));
  mvprintw(LINES - 3, 2,
           "←/→ or A/D: select button, Enter: confirm, ↑/↓: select room, C/Q: "
           "shortcuts");
  refresh();
}

static void update_room_list_dynamic(const RoomEntry *rooms, int room_count,
                                     int selected_room, FocusType focus) {
  int max_width = compute_room_list_max_width(rooms, room_count);
  int start_x = (COLS - max_width) / 2;
  if (start_x < 0)
    start_x = 0;
  int start_y = 6, end_y = LINES - 6;
  if (room_count > 0) {
    for (int i = 0; i < room_count; i++) {
      int y = start_y + i;
      if (y > end_y)
        break;
      if (i == selected_room) {
        attron(A_REVERSE);
        mvprintw(y, start_x, "%-*s", max_width, rooms[i].line);
        attroff(A_REVERSE);
      } else {
        mvprintw(y, start_x, "%-*s", max_width, rooms[i].line);
      }
    }
    for (int y = start_y + room_count; y <= end_y; y++) {
      mvprintw(y, start_x, "%-*s", max_width, "");
    }
  } else {
    mvprintw(start_y, start_x, "%-*s", max_width, "No active rooms.");
    for (int y = start_y + 1; y <= end_y; y++) {
      mvprintw(y, start_x, "%-*s", max_width, "");
    }
  }
  int btn_y = LINES - 4;
  int btn1_len = 14, btn2_len = 10, spacing = 4;
  int total_width = btn1_len + spacing + btn2_len;
  int btn_start_x = (COLS - total_width) / 2;
  mvprintw(btn_y, btn_start_x, "%-*s", btn1_len, "");
  mvprintw(btn_y, btn_start_x + btn1_len + spacing, "%-*s", btn2_len, "");
  attron(COLOR_PAIR(COLOR_PAIR_MENU));
  if (focus == FOCUS_CREATE)
    attron(A_REVERSE);
  mvprintw(btn_y, btn_start_x, "[ Create Room ]");
  if (focus == FOCUS_CREATE)
    attroff(A_REVERSE);
  attroff(COLOR_PAIR(COLOR_PAIR_MENU));
  attron(COLOR_PAIR(COLOR_PAIR_MENU));
  if (focus == FOCUS_LOGOUT)
    attron(A_REVERSE);
  mvprintw(btn_y, btn_start_x + btn1_len + spacing, "[ Logout ]");
  if (focus == FOCUS_LOGOUT)
    attroff(A_REVERSE);
  attroff(COLOR_PAIR(COLOR_PAIR_MENU));
  refresh();
}

static int send_room_operation(ClientContext *ctx, RoomOp op, int room_id) {
  char response[BUFFER_SIZE];
  const char *op_str = (op == OP_CREATE) ? "CREATE" : "JOIN";
  if (send_request_to_main_server(ctx, response, sizeof(response), "%s %d %s",
                                  op_str, room_id, ctx->username) < 0) {
    show_message_centered("Failed to contact main server");
    return STATE_ROOM_LIST;
  }
  if (strncmp(response, "REDIRECT", 8) == 0) {
    int room_port = 0, redirected_room_id = -1;
    sscanf(response + 9, "%d %d", &room_port, &redirected_room_id);
    if (connect_to_room_server(ctx, room_port) == 0) {
      if (ctx->gateway_conn) {
        send_message(ctx->gateway_conn->socket_fd, "QUIT");
        connection_destroy(ctx->gateway_conn);
        ctx->gateway_conn = NULL;
      }
      return STATE_IN_ROOM;
    }
    return STATE_ROOM_LIST;
  } else {
    show_message_centered(response);
    return STATE_ROOM_LIST;
  }
}

static int show_room_menu_with_gateway(ClientContext *ctx) {
  if (ctx->gateway_conn == NULL) {
    int retry = 0, connected = 0;
    while (retry < 3) {
      ctx->gateway_conn = connection_create();
      if (!ctx->gateway_conn) {
        retry++;
        continue;
      }
      if (connection_connect(ctx->gateway_conn, ctx->gateway_ip,
                             ctx->gateway_port) == 0) {
        char user_msg[USERNAME_LEN + 16];
        snprintf(user_msg, sizeof(user_msg), "USER %s", ctx->username);
        send_message(ctx->gateway_conn->socket_fd, user_msg);
        connected = 1;
        break;
      }
      connection_destroy(ctx->gateway_conn);
      ctx->gateway_conn = NULL;
      retry++;
      usleep(500000);
    }
    if (!connected) {
      show_message_centered("Failed to reconnect to gateway after retries");
      return STATE_AUTH;
    }
  }
  RoomEntry rooms[MAX_ROOMS];
  int room_count = 0, selected_room = -1;
  FocusType focus = FOCUS_ROOM_LIST;
  int need_redraw = 1, first_draw = 1;
  time_t last_heartbeat = time(NULL);
  fd_set read_fds;
  while (1) {
    FD_ZERO(&read_fds);
    FD_SET(STDIN_FILENO, &read_fds);
    int max_fd = STDIN_FILENO;
    if (ctx->gateway_conn && ctx->gateway_conn->socket_fd >= 0) {
      FD_SET(ctx->gateway_conn->socket_fd, &read_fds);
      if (ctx->gateway_conn->socket_fd > max_fd)
        max_fd = ctx->gateway_conn->socket_fd;
    }
    struct timeval tv = {1, 0};
    if (select(max_fd + 1, &read_fds, NULL, NULL, &tv) < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    if (ctx->gateway_conn &&
        FD_ISSET(ctx->gateway_conn->socket_fd, &read_fds)) {
      char buffer[BUFFER_SIZE];
      int n =
          recv_line(ctx->gateway_conn->socket_fd, buffer, sizeof(buffer), 0);
      if (n <= 0) {
        show_message_centered("Gateway connection lost");
        connection_destroy(ctx->gateway_conn);
        ctx->gateway_conn = NULL;
        break;
      }
      if (strncmp(buffer, ROOM_LIST_UPDATE_PREFIX,
                  strlen(ROOM_LIST_UPDATE_PREFIX)) == 0) {
        char *content = buffer + strlen(ROOM_LIST_UPDATE_PREFIX);
        parse_room_list(content, rooms, &room_count);
        selected_room = (room_count > 0) ? 0 : -1;
        focus = (room_count > 0) ? FOCUS_ROOM_LIST : FOCUS_CREATE;
        need_redraw = 1;
      }
    }
    time_t now = time(NULL);
    if (ctx->gateway_conn && now - last_heartbeat >= HEARTBEAT_INTERVAL) {
      send_message(ctx->gateway_conn->socket_fd, "PING");
      last_heartbeat = now;
    }
    if (FD_ISSET(STDIN_FILENO, &read_fds)) {
      int ch = getch();
      switch (ch) {
      case KEY_UP:
        if (room_count > 0) {
          selected_room = (selected_room - 1 + room_count) % room_count;
          focus = FOCUS_ROOM_LIST;
          need_redraw = 1;
        }
        break;
      case KEY_DOWN:
        if (room_count > 0) {
          selected_room = (selected_room + 1) % room_count;
          focus = FOCUS_ROOM_LIST;
          need_redraw = 1;
        }
        break;
      case KEY_LEFT:
      case 'a':
      case 'A':
        focus = (focus == FOCUS_CREATE) ? FOCUS_LOGOUT : FOCUS_CREATE;
        need_redraw = 1;
        break;
      case KEY_RIGHT:
      case 'd':
      case 'D':
        focus = (focus == FOCUS_LOGOUT) ? FOCUS_CREATE : FOCUS_LOGOUT;
        need_redraw = 1;
        break;
      case '\n':
      case KEY_ENTER:
        if (focus == FOCUS_ROOM_LIST && selected_room != -1) {
          int room_id = rooms[selected_room].id;
          int next_state = send_room_operation(ctx, OP_JOIN, room_id);
          if (next_state == STATE_IN_ROOM)
            return next_state;
          focus = FOCUS_ROOM_LIST;
          need_redraw = 1;
        } else if (focus == FOCUS_CREATE) {
          clear();
          draw_border();
          draw_status_bar(ctx);
          mvprintw(3, (COLS - 15) / 2, "Create Room");
          refresh();
          mvprintw(5, 2, "Enter room id (0-%d): ", MAX_ROOMS - 1);
          int room_id = read_int_input_ncurses(0, MAX_ROOMS - 1);
          if (room_id != -1) {
            int next_state = send_room_operation(ctx, OP_CREATE, room_id);
            if (next_state == STATE_IN_ROOM)
              return next_state;
          }
          focus = FOCUS_ROOM_LIST;
          need_redraw = 1;
        } else if (focus == FOCUS_LOGOUT) {
          send_request_to_main_server(ctx, NULL, 0, "LOGOUT %s", ctx->username);
          if (ctx->gateway_conn) {
            send_message(ctx->gateway_conn->socket_fd, "QUIT");
            connection_destroy(ctx->gateway_conn);
            ctx->gateway_conn = NULL;
          }
          ctx->is_logged_in = 0;
          return STATE_AUTH;
        }
        break;
      case 'c':
      case 'C':
        clear();
        draw_border();
        draw_status_bar(ctx);
        mvprintw(3, (COLS - 15) / 2, "Create Room");
        refresh();
        mvprintw(5, 2, "Enter room id (0-%d): ", MAX_ROOMS - 1);
        int room_id = read_int_input_ncurses(0, MAX_ROOMS - 1);
        if (room_id != -1) {
          int next_state = send_room_operation(ctx, OP_CREATE, room_id);
          if (next_state == STATE_IN_ROOM)
            return next_state;
        }
        focus = FOCUS_ROOM_LIST;
        need_redraw = 1;
        break;
      case 'q':
      case 'Q':
        send_request_to_main_server(ctx, NULL, 0, "LOGOUT %s", ctx->username);
        if (ctx->gateway_conn) {
          send_message(ctx->gateway_conn->socket_fd, "QUIT");
          connection_destroy(ctx->gateway_conn);
          ctx->gateway_conn = NULL;
        }
        ctx->is_logged_in = 0;
        return STATE_AUTH;
      default:
        break;
      }
    }
    if (first_draw) {
      draw_room_list_menu(rooms, room_count, selected_room, ctx, focus);
      first_draw = 0;
    } else if (need_redraw) {
      update_room_list_dynamic(rooms, room_count, selected_room, focus);
      need_redraw = 0;
    }
  }
  return STATE_AUTH;
}

/* ---------------------------- 游戏界面（JSON适配版）
 * ---------------------------- */
static int connect_to_room_server(ClientContext *ctx, int port) {
  ctx->room_conn = connection_create();
  if (!ctx->room_conn) {
    show_message_centered("Failed to create room connection");
    return -1;
  }
  int attempts = 0;
  while (attempts < MAX_RETRY_ATTEMPTS) {
    if (connection_connect(ctx->room_conn, ctx->server_ip, port) >= 0)
      break;
    usleep(RETRY_DELAY_MS * 1000);
    attempts++;
  }
  if (attempts == MAX_RETRY_ATTEMPTS) {
    show_message_centered("Failed to connect to room server");
    connection_destroy(ctx->room_conn);
    ctx->room_conn = NULL;
    return -1;
  }
  char player_info[BUFFER_SIZE];
  snprintf(player_info, sizeof(player_info), "PLAYER %s", ctx->username);
  send_message(ctx->room_conn->socket_fd, player_info);
  return 0;
}

static void draw_static_map(const GameStateData *state) {
  int map_start_y = MAP_RENDER_START_Y;
  attron(COLOR_PAIR(COLOR_PAIR_WALL));
  for (int x = 0; x < MAP_WIDTH; x++) {
    mvaddch(map_start_y, x + 1, '#');
    mvaddch(map_start_y + MAP_HEIGHT - 1, x + 1, '#');
  }
  for (int y = 0; y < MAP_HEIGHT; y++) {
    mvaddch(map_start_y + y, 1, '#');
    mvaddch(map_start_y + y, MAP_WIDTH, '#');
  }
  attroff(COLOR_PAIR(COLOR_PAIR_WALL));
  attron(COLOR_PAIR(COLOR_PAIR_OBSTACLE));
  for (int i = 0; i < state->obstacle_count; i++) {
    int x = state->obstacles[i].x;
    int y = state->obstacles[i].y;
    if (x >= 0 && x < MAP_WIDTH && y >= 0 && y < MAP_HEIGHT) {
      mvaddch(map_start_y + y, x + 1, 'X');
    }
  }
  attroff(COLOR_PAIR(COLOR_PAIR_OBSTACLE));
  static_map_drawn = 1;
}

static void draw_char_at(int x, int y, char c) {
  int map_start_y = MAP_RENDER_START_Y;
  int color_pair = COLOR_PAIR_NORMAL;
  if (c == '@' || c == 'o')
    color_pair = COLOR_PAIR_PLAYER;
  else if (c == '*')
    color_pair = COLOR_PAIR_FOOD;
  else if (c == 'X')
    color_pair = COLOR_PAIR_OBSTACLE;
  else if (c == '#')
    color_pair = COLOR_PAIR_WALL;
  attron(COLOR_PAIR(color_pair));
  mvaddch(map_start_y + y, x + 1, c);
  attroff(COLOR_PAIR(color_pair));
}

static void erase_char_at(int x, int y) { draw_char_at(x, y, ' '); }

static int find_snake_cache_index(const char *name) {
  for (int i = 0; i < prev_snake_count; i++) {
    if (strcmp(prev_snakes[i].name, name) == 0)
      return i;
  }
  return -1;
}

static void render_game_state(const GameStateData *state) {
  int map_start_y = MAP_RENDER_START_Y;
  if (!static_map_drawn)
    draw_static_map(state);
  SnakeCache new_snakes[MAX_PLAYERS_PER_ROOM];
  int new_snake_count = state->player_count;
  for (int i = 0; i < new_snake_count; i++) {
    strcpy(new_snakes[i].name, state->players[i].name);
    new_snakes[i].length = state->players[i].length;
    new_snakes[i].is_dead = state->players[i].is_dead;
    for (int j = 0; j < state->players[i].length; j++) {
      new_snakes[i].body[j] = state->players[i].body[j];
    }
  }
  for (int i = 0; i < new_snake_count; i++) {
    int idx = find_snake_cache_index(new_snakes[i].name);
    if (idx == -1) {
      for (int j = 0; j < new_snakes[i].length; j++) {
        int x = new_snakes[i].body[j].x, y = new_snakes[i].body[j].y;
        char c = (j == 0) ? '@' : 'o';
        if (new_snakes[i].is_dead)
          c = (j == 0) ? 'X' : 'x';
        draw_char_at(x, y, c);
      }
    } else {
      SnakeCache *prev = &prev_snakes[idx];
      if (new_snakes[i].is_dead && !prev->is_dead) {
        for (int j = 0; j < prev->length; j++) {
          char c = (j == 0) ? 'X' : 'x';
          draw_char_at(prev->body[j].x, prev->body[j].y, c);
        }
      } else if (new_snakes[i].length > prev->length &&
                 new_snakes[i].body[new_snakes[i].length - 1].x ==
                     prev->body[prev->length - 1].x &&
                 new_snakes[i].body[new_snakes[i].length - 1].y ==
                     prev->body[prev->length - 1].y) {
        draw_char_at(new_snakes[i].body[0].x, new_snakes[i].body[0].y, '@');
        if (prev->length >= 1)
          draw_char_at(prev->body[0].x, prev->body[0].y, 'o');
      } else if (new_snakes[i].length == prev->length &&
                 !new_snakes[i].is_dead) {
        erase_char_at(prev->body[prev->length - 1].x,
                      prev->body[prev->length - 1].y);
        draw_char_at(new_snakes[i].body[0].x, new_snakes[i].body[0].y, '@');
        if (prev->length > 1)
          draw_char_at(prev->body[0].x, prev->body[0].y, 'o');
      }
    }
  }
  for (int i = 0; i < prev_snake_count; i++) {
    int found = 0;
    for (int j = 0; j < new_snake_count; j++) {
      if (strcmp(prev_snakes[i].name, new_snakes[j].name) == 0) {
        found = 1;
        break;
      }
    }
    if (!found) {
      for (int j = 0; j < prev_snakes[i].length; j++) {
        erase_char_at(prev_snakes[i].body[j].x, prev_snakes[i].body[j].y);
      }
    }
  }
  if (prev_food.x != state->food.x || prev_food.y != state->food.y) {
    if (prev_food.x != -1)
      erase_char_at(prev_food.x, prev_food.y);
    draw_char_at(state->food.x, state->food.y, '*');
  }
  int info_x = INFO_PANEL_X_OFFSET, info_y = map_start_y;
  for (int line = 0; line < 10; line++)
    mvprintw(info_y + line, info_x, "%-40s", "");
  mvprintw(info_y, info_x, "=== Players (%d/%d) ===", state->active_players,
           state->total_players);
  info_y += 2;
  for (int i = 0; i < state->player_count; i++) {
    const char *status = state->players[i].is_dead ? "DEAD" : "ALIVE";
    const char *you = state->players[i].is_you ? " (YOU)" : "";
    mvprintw(info_y + i, info_x, "%s: Score=%d, Len=%d, %s%s",
             state->players[i].name, state->players[i].score,
             state->players[i].length, status, you);
  }
  memcpy(prev_snakes, new_snakes, sizeof(SnakeCache) * new_snake_count);
  prev_snake_count = new_snake_count;
  prev_food = state->food;
  refresh();
}

/* JSON 反序列化 */
static int deserialize_game_state_json(const char *json, GameStateData *state) {
  cJSON *root = cJSON_Parse(json);
  if (!root)
    return -1;
  cJSON *type = cJSON_GetObjectItem(root, "type");
  if (!type || strcmp(type->valuestring, "STATE") != 0) {
    cJSON_Delete(root);
    return -1;
  }
  cJSON *data = cJSON_GetObjectItem(root, "data");
  if (!data) {
    cJSON_Delete(root);
    return -1;
  }
  cJSON *roomId = cJSON_GetObjectItem(data, "roomId");
  if (roomId)
    state->room_id = roomId->valueint;
  cJSON *food = cJSON_GetObjectItem(data, "food");
  if (food) {
    cJSON *x = cJSON_GetObjectItem(food, "x");
    cJSON *y = cJSON_GetObjectItem(food, "y");
    if (x && y) {
      state->food.x = x->valueint;
      state->food.y = y->valueint;
    }
  }
  cJSON *obstacles = cJSON_GetObjectItem(data, "obstacles");
  if (obstacles && cJSON_IsArray(obstacles)) {
    state->obstacle_count = cJSON_GetArraySize(obstacles);
    if (state->obstacle_count > OBSTACLE_COUNT)
      state->obstacle_count = OBSTACLE_COUNT;
    for (int i = 0; i < state->obstacle_count; i++) {
      cJSON *obs = cJSON_GetArrayItem(obstacles, i);
      if (obs) {
        cJSON *ox = cJSON_GetObjectItem(obs, "x");
        cJSON *oy = cJSON_GetObjectItem(obs, "y");
        if (ox && oy) {
          state->obstacles[i].x = ox->valueint;
          state->obstacles[i].y = oy->valueint;
        }
      }
    }
  }
  cJSON *players = cJSON_GetObjectItem(data, "players");
  if (players && cJSON_IsArray(players)) {
    state->player_count = cJSON_GetArraySize(players);
    if (state->player_count > MAX_PLAYERS_PER_ROOM)
      state->player_count = MAX_PLAYERS_PER_ROOM;
    for (int i = 0; i < state->player_count; i++) {
      cJSON *p = cJSON_GetArrayItem(players, i);
      if (!p)
        continue;
      cJSON *name = cJSON_GetObjectItem(p, "name");
      if (name)
        strncpy(state->players[i].name, name->valuestring, USERNAME_LEN - 1);
      cJSON *head = cJSON_GetObjectItem(p, "head");
      if (head) {
        cJSON *hx = cJSON_GetObjectItem(head, "x");
        cJSON *hy = cJSON_GetObjectItem(head, "y");
        if (hx && hy) {
          state->players[i].head.x = hx->valueint;
          state->players[i].head.y = hy->valueint;
        }
      }
      cJSON *body = cJSON_GetObjectItem(p, "body");
      if (body && cJSON_IsArray(body)) {
        int len = cJSON_GetArraySize(body);
        state->players[i].length =
            (len > MAX_SNAKE_LENGTH) ? MAX_SNAKE_LENGTH : len;
        for (int j = 0; j < state->players[i].length; j++) {
          cJSON *seg = cJSON_GetArrayItem(body, j);
          if (seg) {
            cJSON *sx = cJSON_GetObjectItem(seg, "x");
            cJSON *sy = cJSON_GetObjectItem(seg, "y");
            if (sx && sy) {
              state->players[i].body[j].x = sx->valueint;
              state->players[i].body[j].y = sy->valueint;
            }
          }
        }
      }
      cJSON *direction = cJSON_GetObjectItem(p, "direction");
      if (direction) {
        const char *dir_str = direction->valuestring;
        if (strcmp(dir_str, "UP") == 0)
          state->players[i].direction = 0;
        else if (strcmp(dir_str, "DOWN") == 0)
          state->players[i].direction = 1;
        else if (strcmp(dir_str, "LEFT") == 0)
          state->players[i].direction = 2;
        else if (strcmp(dir_str, "RIGHT") == 0)
          state->players[i].direction = 3;
      }
      cJSON *score = cJSON_GetObjectItem(p, "score");
      if (score)
        state->players[i].score = score->valueint;
      cJSON *isDead = cJSON_GetObjectItem(p, "isDead");
      if (isDead)
        state->players[i].is_dead = isDead->valueint;
      cJSON *isYou = cJSON_GetObjectItem(p, "isYou");
      if (isYou)
        state->players[i].is_you = isYou->valueint;
    }
  }
  cJSON *active = cJSON_GetObjectItem(data, "activePlayers");
  if (active)
    state->active_players = active->valueint;
  cJSON *total = cJSON_GetObjectItem(data, "totalPlayers");
  if (total)
    state->total_players = total->valueint;
  cJSON_Delete(root);
  return 0;
}

static int game_loop_ncurses(ClientContext *ctx) {
  nodelay(stdscr, TRUE);
  fd_set read_fds;
  int max_fd = ctx->room_conn->socket_fd;
  if (STDIN_FILENO > max_fd)
    max_fd = STDIN_FILENO;
  int game_active = 1;
  static_map_drawn = 0;
  prev_snake_count = 0;
  prev_food.x = -1;
  prev_food.y = -1;
  char recv_buf[RECV_BUFFER_SIZE];
  while (game_active) {
    FD_ZERO(&read_fds);
    FD_SET(ctx->room_conn->socket_fd, &read_fds);
    FD_SET(STDIN_FILENO, &read_fds);
    struct timeval tv = {0, SELECT_TIMEOUT_US};
    if (select(max_fd + 1, &read_fds, NULL, NULL, &tv) < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    if (FD_ISSET(STDIN_FILENO, &read_fds)) {
      int ch = getch();
      if (ch == 'q' || ch == 'Q' || ch == KEY_EXIT) {
        send_message(ctx->room_conn->socket_fd, "Q");
        game_active = 0;
        break;
      }
      char dir = 0;
      if (ch == KEY_UP || ch == 'w' || ch == 'W')
        dir = 'w';
      else if (ch == KEY_DOWN || ch == 's' || ch == 'S')
        dir = 's';
      else if (ch == KEY_LEFT || ch == 'a' || ch == 'A')
        dir = 'a';
      else if (ch == KEY_RIGHT || ch == 'd' || ch == 'D')
        dir = 'd';
      if (dir)
        send_message(ctx->room_conn->socket_fd, (char[]){dir, '\0'});
    }
    if (FD_ISSET(ctx->room_conn->socket_fd, &read_fds)) {
      int n =
          recv_line(ctx->room_conn->socket_fd, recv_buf, sizeof(recv_buf), 0);
      if (n > 0) {
        recv_buf[n] = '\0';
        if (recv_buf[0] == '{') {
          GameStateData state_data;
          if (deserialize_game_state_json(recv_buf, &state_data) == 0) {
            render_game_state(&state_data);
            for (int i = 0; i < state_data.player_count; i++) {
              if (state_data.players[i].is_you &&
                  state_data.players[i].is_dead) {
                clear();
                mvprintw(0, 0, "You died! Final Score: %d",
                         state_data.players[i].score);
                mvprintw(1, 0, "Press Q to quit or wait for game to end.");
                refresh();
              }
            }
          }
        } else if (strstr(recv_buf, "WELCOME TO ROOM") != NULL) {
          // welcome message, ignore
        } else if (strcmp(recv_buf, "YOU DIED") == 0) {
          clear();
          mvprintw(0, 0, "You died!");
          refresh();
        } else {
          clear();
          mvprintw(0, 0, "%s", recv_buf);
          refresh();
        }
      } else if (n == 0) {
        clear();
        mvprintw(0, 0, "Server disconnected. Returning to room list...");
        refresh();
        napms(2000);
        game_active = 0;
        break;
      } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
        game_active = 0;
        break;
      }
    }
  }
  nodelay(stdscr, FALSE);
  return STATE_GAME_OVER;
}

/* ---------------------------- 初始化/清理 ---------------------------- */
static void init_client_context(ClientContext *ctx, const char *ip, int port) {
  memset(ctx, 0, sizeof(ClientContext));
  ctx->state = STATE_AUTH;
  strncpy(ctx->server_ip, ip, sizeof(ctx->server_ip) - 1);
  ctx->server_ip[sizeof(ctx->server_ip) - 1] = '\0';
  ctx->server_port = port;
}

static void cleanup_client_context(ClientContext *ctx) {
  if (ctx->room_conn) {
    connection_destroy(ctx->room_conn);
    ctx->room_conn = NULL;
  }
  if (ctx->gateway_conn) {
    connection_destroy(ctx->gateway_conn);
    ctx->gateway_conn = NULL;
  }
}

/* ---------------------------- 主函数 ---------------------------- */
int main(int argc, char **argv) {
  if (argc != 3) {
    fprintf(stderr, "Usage: %s <server_ip> <server_port>\n", argv[0]);
    return EXIT_FAILURE;
  }
  const char *server_ip = argv[1];
  int server_port = atoi(argv[2]);
  initscr();
  cbreak();
  noecho();
  keypad(stdscr, TRUE);
  curs_set(0);
  init_color_pairs();
  ClientContext ctx;
  init_client_context(&ctx, server_ip, server_port);
  while (ctx.state != STATE_EXIT) {
    draw_status_bar(&ctx);
    switch (ctx.state) {
    case STATE_AUTH:
      ctx.state = show_auth_menu_ncurses(&ctx);
      break;
    case STATE_ROOM_LIST:
      ctx.state = show_room_menu_with_gateway(&ctx);
      break;
    case STATE_IN_ROOM:
      ctx.state = game_loop_ncurses(&ctx);
      cleanup_client_context(&ctx);
      break;
    case STATE_GAME_OVER:
      show_message_centered("Game ended. Returning to room list...");
      ctx.state = STATE_ROOM_LIST;
      break;
    default:
      ctx.state = STATE_EXIT;
      break;
    }
  }
  cleanup_client_context(&ctx);
  endwin();
  return EXIT_SUCCESS;
}
