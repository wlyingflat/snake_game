/**
 * client.c - 贪吃蛇游戏客户端（网关推送版，修复 Logout 和粘包问题）
 *
 * 功能：提供基于 ncurses
 * 的终端界面，处理用户交互，登录后连接网关接收实时房间列表更新。
 * 修改：房间列表固定表头，网关仅推送数据行，减少刷新区域和网络数据量。
 *       增加进入房间列表时的初始强制绘制，确保表头立即显示。
 *       新增：房间列表支持上下键选择，回车直接加入。
 *       新增：底部横向菜单（Create / Logout），左右键或A/D选择，回车执行。
 *       新增：局部更新机制，列表更新时只重绘数据行和按钮，保留表头。
 *       修复：局部更新时避免清除边框，使用固定宽度打印覆盖。
 *       新增：认证界面同时显示用户名和密码输入框，上下键切换焦点，局部刷新。
 *   [2025-03-XX] 修改登录菜单居中显示，选项左对齐。
 *   [2025-03-XX] 游戏画面改为局部刷新：只更新变化的蛇身、食物，保留静态元素。
 *   [2025-03-XX] 房间列表改为居中显示（表头和数据行整体居中）。
 */

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <ncurses.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "config.h"
#include "network.h"

/* ---------------------------- 常量定义 ---------------------------- */
#define SELECT_TIMEOUT_US 10000
#define STATUS_BAR_Y (LINES - 1)            /* 状态栏位置 */
#define MENU_START_Y 5                      /* 菜单起始行 */
#define MAP_RENDER_START_Y 3                /* 地图绘制起始行 */
#define INFO_PANEL_X_OFFSET (MAP_WIDTH + 5) /* 右侧信息面板起始列 */

/* 菜单选项数量 */
#define AUTH_MENU_COUNT 3
#define ROOM_MENU_COUNT 2 /* 只剩两个：创建房间、登出 */

/* 操作类型枚举 */
typedef enum { OP_CREATE, OP_JOIN } RoomOp;

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

/* 焦点区域（房间列表/按钮） */
typedef enum { FOCUS_ROOM_LIST, FOCUS_CREATE, FOCUS_LOGOUT } FocusType;

/* 认证表单焦点 */
typedef enum {
  AUTH_FOCUS_USERNAME,
  AUTH_FOCUS_PASSWORD,
  AUTH_FOCUS_SUBMIT
} AuthFocus;

/* ---------------------------- 状态枚举 ---------------------------- */
typedef enum {
  STATE_AUTH,
  STATE_ROOM_LIST,
  STATE_IN_ROOM,
  STATE_GAME_OVER,
  STATE_EXIT
} ClientState;

/* ---------------------------- 客户端上下文 ---------------------------- */
typedef struct {
  ClientState state;
  char username[USERNAME_LEN];
  char password[USERNAME_LEN];
  int is_logged_in;
  Connection *room_conn;    /* 房间服务器连接（游戏中） */
  Connection *gateway_conn; /* 网关长连接（房间列表时） */
  char server_ip[64];
  int server_port;
  char gateway_ip[64];
  int gateway_port;
} ClientContext;

/* ---------------------------- 游戏画面缓存（新增）
 * ---------------------------- */
typedef struct {
  int x, y;
} Point;

typedef struct {
  char name[USERNAME_LEN];
  Point body[MAX_SNAKE_LENGTH];
  int length;
  int is_dead;
} SnakeCache;

static SnakeCache prev_snakes[MAX_PLAYERS_PER_ROOM];
static int prev_snake_count = 0;
static Point prev_food = {-1, -1};
static int static_map_drawn = 0; /* 静态地图（墙壁+障碍物）是否已绘制 */

/* ---------------------------- 函数声明 ---------------------------- */
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
static int show_room_menu_with_gateway(ClientContext *ctx);
static int game_loop_ncurses(ClientContext *ctx);
static void render_game_state(const GameStateData *state_data);
static void
draw_static_map(const GameStateData *state_data); /* 新增：绘制静态元素 */

static void init_client_context(ClientContext *ctx, const char *ip, int port);
static void cleanup_client_context(ClientContext *ctx);

/* 新增：认证表单输入函数 */
static int input_string(int y, int x, char *buffer, int max_len, int echo);
static int auth_input_form(ClientContext *ctx, int is_register);

/* 房间条目结构及解析函数 */
typedef struct {
  int id;
  char line[256];
} RoomEntry;

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
  // 最后一行可能没有换行符
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

/* ---------------------------- 辅助函数（美化） ---------------------------- */

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

static void draw_border(void) {
  attron(COLOR_PAIR(COLOR_PAIR_BORDER));
  border(0, 0, 0, 0, 0, 0, 0, 0);
  attroff(COLOR_PAIR(COLOR_PAIR_BORDER));
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
  for (int i = 0; i < 5; i++) {
    mvhline(start_y + i, start_x, ' ', box_width);
  }
  attroff(COLOR_PAIR(COLOR_PAIR_BORDER));

  attron(COLOR_PAIR(COLOR_PAIR_TITLE));
  mvprintw(start_y + 2, start_x + 2, "%s", msg);
  attroff(COLOR_PAIR(COLOR_PAIR_TITLE));

  mvprintw(start_y + 4, start_x + 2, "Press any key");
  refresh();
  getch();
}

/* ---------------------------- 通用菜单绘制 ---------------------------- */
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

/* ---------------------------- 网络辅助函数 ---------------------------- */

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
  int n = receive_message(conn->socket_fd, response, sizeof(response),
                          RECV_TIMEOUT * 1000);
  if (n <= 0) {
    connection_destroy(conn);
    return -1;
  }
  response[n] = '\0';
  if (response_buf && buf_size > 0) {
    strncpy(response_buf, response, buf_size - 1);
    response_buf[buf_size - 1] = '\0';
  }
  connection_destroy(conn);
  return 0;
}

static int connect_and_send_auth(ClientContext *ctx, int is_register) {
  char response[BUFFER_SIZE];
  const char *cmd = is_register ? "REG" : "LOGIN";
  if (send_request_to_main_server(ctx, response, sizeof(response), "%s %s %s",
                                  cmd, ctx->username, ctx->password) < 0) {
    show_message_centered("Failed to communicate with server");
    return STATE_AUTH;
  }

  char *saveptr;
  char *line = strtok_r(response, "\n", &saveptr);
  if (strcmp(line, "OK Login successful") != 0 &&
      strcmp(line, "OK Registration successful") != 0) {
    show_message_centered(line);
    return STATE_AUTH;
  }

  line = strtok_r(NULL, "\n", &saveptr);
  if (!line || sscanf(line, "GATEWAY %63s %d", ctx->gateway_ip,
                      &ctx->gateway_port) != 2) {
    show_message_centered("Invalid gateway info from server");
    return STATE_AUTH;
  }

  ctx->gateway_conn = connection_create();
  if (!ctx->gateway_conn) {
    show_message_centered("Failed to create gateway connection");
    return STATE_AUTH;
  }
  if (connection_connect(ctx->gateway_conn, ctx->gateway_ip,
                         ctx->gateway_port) < 0) {
    show_message_centered("Failed to connect to gateway");
    connection_destroy(ctx->gateway_conn);
    ctx->gateway_conn = NULL;
    return STATE_AUTH;
  }

  char user_msg[USERNAME_LEN + 16];
  snprintf(user_msg, sizeof(user_msg), "USER %s", ctx->username);
  send_message(ctx->gateway_conn->socket_fd, user_msg);

  ctx->is_logged_in = 1;
  return STATE_ROOM_LIST;
}

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

/* ---------------------------- 认证菜单 ---------------------------- */
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
    draw_menu_options(options, AUTH_MENU_COUNT, selected, MENU_START_Y);
    mvprintw(LINES - 4, 2, "Use Up/Down arrows to navigate, Enter to select.");
    refresh();

    int ch = getch();
    switch (ch) {
    case KEY_UP:
      selected = (selected - 1 + AUTH_MENU_COUNT) % AUTH_MENU_COUNT;
      break;
    case KEY_DOWN:
      selected = (selected + 1) % AUTH_MENU_COUNT;
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

/* ---------------------------- 认证表单输入 ---------------------------- */
static int input_string(int y, int x, char *buffer, int max_len, int echo) {
  int pos = 0;
  int ch;
  buffer[0] = '\0';

  move(y, x);
  curs_set(1);

  while (1) {
    ch = getch();
    if (ch == '\n' || ch == KEY_ENTER) {
      break;
    } else if (ch == KEY_BACKSPACE || ch == 127 || ch == 8) {
      if (pos > 0) {
        pos--;
        move(y, x + pos);
        addch(' ');
        move(y, x + pos);
        refresh();
      }
    } else if (isprint(ch) && pos < max_len - 1) {
      buffer[pos++] = ch;
      if (echo) {
        addch(ch);
      } else {
        addch('*');
      }
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

  int start_y = 7;
  int label_x = COLS / 2 - 15;
  int input_x = label_x + 12;

  mvprintw(start_y, label_x, "Username:");
  mvprintw(start_y + 2, label_x, "Password:");

  int btn_y = start_y + 5;
  int btn_x = COLS / 2 - 5;
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
        if (next_state == STATE_ROOM_LIST || next_state == STATE_AUTH) {
          return next_state;
        }
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

/* ----------------------------
 * 房间列表相关（居中显示）---------------------------- */

/**
 * 计算房间列表最大行宽（包括表头）
 */
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

  // 如果没有房间，考虑 "No active rooms." 的长度
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

  // 表头
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

  // 底部按钮（保持原位置，居中于屏幕）
  int btn_y = LINES - 4;
  int btn1_len = 14;
  int btn2_len = 10;
  int spacing = 4;
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
           "←/→ or A/D: select button, Enter: confirm, ↑/↓: select room, "
           "C/Q: shortcuts");
  refresh();
}

static void update_room_list_dynamic(const RoomEntry *rooms, int room_count,
                                     int selected_room, FocusType focus) {
  int max_width = compute_room_list_max_width(rooms, room_count);
  int start_x = (COLS - max_width) / 2;
  if (start_x < 0)
    start_x = 0;

  int start_y = 6;
  int end_y = LINES - 6;

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
    // 清除下方多余行
    for (int y = start_y + room_count; y <= end_y; y++) {
      mvprintw(y, start_x, "%-*s", max_width, "");
    }
  } else {
    mvprintw(start_y, start_x, "%-*s", max_width, "No active rooms.");
    for (int y = start_y + 1; y <= end_y; y++) {
      mvprintw(y, start_x, "%-*s", max_width, "");
    }
  }

  // 底部按钮（保持原位置）
  int btn_y = LINES - 4;
  int btn1_len = 14;
  int btn2_len = 10;
  int spacing = 4;
  int total_width = btn1_len + spacing + btn2_len;
  int btn_start_x = (COLS - total_width) / 2;

  // 先清除按钮区域（用空格覆盖）
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

static int show_room_menu_with_gateway(ClientContext *ctx) {
  if (ctx->gateway_conn == NULL) {
    int retry = 0;
    int connected = 0;
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
  int room_count = 0;
  int selected_room = -1;
  FocusType focus = FOCUS_ROOM_LIST;
  int need_redraw = 1;
  int first_draw = 1;

  time_t last_heartbeat = time(NULL);
  struct timeval tv;
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

    tv.tv_sec = 1;
    tv.tv_usec = 0;

    if (select(max_fd + 1, &read_fds, NULL, NULL, &tv) < 0) {
      if (errno == EINTR)
        continue;
      break;
    }

    if (ctx->gateway_conn &&
        FD_ISSET(ctx->gateway_conn->socket_fd, &read_fds)) {
      char buffer[BUFFER_SIZE];
      int n = receive_message(ctx->gateway_conn->socket_fd, buffer,
                              sizeof(buffer), 0);
      if (n <= 0) {
        show_message_centered("Gateway connection lost");
        connection_destroy(ctx->gateway_conn);
        ctx->gateway_conn = NULL;
        break;
      }

      if (strncmp(buffer, "ROOM_LIST_UPDATE|", 17) == 0) {
        char *content = buffer + 17;
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
      case 'C': {
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
      }
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

/* ---------------------------- 游戏界面（局部刷新版）
 * ---------------------------- */

/**
 * 绘制静态地图元素（墙壁、障碍物），只在首次进入房间或需要重置时调用。
 */
static void draw_static_map(const GameStateData *state_data) {
  int map_start_y = MAP_RENDER_START_Y;

  // 绘制墙壁（边界）
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

  // 绘制障碍物
  attron(COLOR_PAIR(COLOR_PAIR_OBSTACLE));
  for (int i = 0; i < state_data->obstacle_count; i++) {
    int x = state_data->obstacles[i].x;
    int y = state_data->obstacles[i].y;
    if (x >= 0 && x < MAP_WIDTH && y >= 0 && y < MAP_HEIGHT) {
      mvaddch(map_start_y + y, x + 1, 'X');
    }
  }
  attroff(COLOR_PAIR(COLOR_PAIR_OBSTACLE));

  static_map_drawn = 1;
}

/**
 * 辅助函数：在指定坐标绘制一个字符（考虑颜色）
 */
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
  else if (c == ' ')
    color_pair = COLOR_PAIR_NORMAL; // 空格使用普通颜色

  attron(COLOR_PAIR(color_pair));
  mvaddch(map_start_y + y, x + 1, c);
  attroff(COLOR_PAIR(color_pair));
}

/**
 * 辅助函数：擦除一个坐标（绘制空格）
 */
static void erase_char_at(int x, int y) { draw_char_at(x, y, ' '); }

/**
 * 比较两条蛇是否相同（通过名字）
 */
static int find_snake_cache_index(const char *name) {
  for (int i = 0; i < prev_snake_count; i++) {
    if (strcmp(prev_snakes[i].name, name) == 0)
      return i;
  }
  return -1;
}

/**
 * 更新游戏画面（局部刷新）
 */
static void render_game_state(const GameStateData *state_data) {
  int map_start_y = MAP_RENDER_START_Y;

  // 首次进入游戏，绘制静态地图（墙壁+障碍物）
  if (!static_map_drawn) {
    draw_static_map(state_data);
  }

  // 准备新蛇缓存数组（用于比较后更新）
  SnakeCache new_snakes[MAX_PLAYERS_PER_ROOM];
  int new_snake_count = state_data->player_count;
  for (int i = 0; i < new_snake_count; i++) {
    strcpy(new_snakes[i].name, state_data->players[i].name);
    new_snakes[i].length = state_data->players[i].length;
    new_snakes[i].is_dead = state_data->players[i].is_dead;
    for (int j = 0; j < state_data->players[i].length; j++) {
      new_snakes[i].body[j].x = state_data->players[i].body[j].x;
      new_snakes[i].body[j].y = state_data->players[i].body[j].y;
    }
  }

  /* ---------- 处理蛇的变化 ---------- */
  for (int i = 0; i < new_snake_count; i++) {
    int idx = find_snake_cache_index(new_snakes[i].name);
    if (idx == -1) {
      // 新玩家加入：绘制整条蛇
      for (int j = 0; j < new_snakes[i].length; j++) {
        int x = new_snakes[i].body[j].x;
        int y = new_snakes[i].body[j].y;
        char c = (j == 0) ? '@' : 'o';
        if (new_snakes[i].is_dead)
          c = (j == 0) ? 'X' : 'x';
        draw_char_at(x, y, c);
      }
    } else {
      // 已有玩家，比较变化
      SnakeCache *prev = &prev_snakes[idx];

      // 情况1：蛇死亡
      if (new_snakes[i].is_dead && !prev->is_dead) {
        // 将 prev 的所有身体格子更新为死亡符号
        for (int j = 0; j < prev->length; j++) {
          int x = prev->body[j].x;
          int y = prev->body[j].y;
          char c = (j == 0) ? 'X' : 'x';
          draw_char_at(x, y, c);
        }
      }
      // 情况2：吃到食物（长度增加且旧蛇尾未变）
      else if (new_snakes[i].length > prev->length &&
               new_snakes[i].body[new_snakes[i].length - 1].x ==
                   prev->body[prev->length - 1].x &&
               new_snakes[i].body[new_snakes[i].length - 1].y ==
                   prev->body[prev->length - 1].y) {
        // 绘制新蛇头（原食物位置）
        int head_x = new_snakes[i].body[0].x;
        int head_y = new_snakes[i].body[0].y;
        draw_char_at(head_x, head_y, '@');

        // 原蛇头位置变为身体
        if (prev->length >= 1) {
          draw_char_at(prev->body[0].x, prev->body[0].y, 'o');
        }

        // 身体其他部分不变，无需重绘
      }
      // 情况3：正常移动（长度不变或减少（死亡已处理））
      else if (new_snakes[i].length == prev->length && !new_snakes[i].is_dead) {
        // 擦除旧尾部
        int old_tail_x = prev->body[prev->length - 1].x;
        int old_tail_y = prev->body[prev->length - 1].y;
        erase_char_at(old_tail_x, old_tail_y);

        // 绘制新蛇头
        int new_head_x = new_snakes[i].body[0].x;
        int new_head_y = new_snakes[i].body[0].y;
        draw_char_at(new_head_x, new_head_y, '@');

        // 原蛇头变为身体（如果蛇长度>1）
        if (prev->length > 1) {
          draw_char_at(prev->body[0].x, prev->body[0].y, 'o');
        }

        // 注意：如果蛇长度=1，原蛇头即旧尾部，已被擦除，新头部绘制即可
      }
      // 其他情况（例如长度减少但未死？理论上不会发生，除非服务器异常）
    }
  }

  /* ---------- 处理消失的蛇（已退出或死亡后被移除） ---------- */
  for (int i = 0; i < prev_snake_count; i++) {
    int found = 0;
    for (int j = 0; j < new_snake_count; j++) {
      if (strcmp(prev_snakes[i].name, new_snakes[j].name) == 0) {
        found = 1;
        break;
      }
    }
    if (!found) {
      // 玩家离开，擦除整条蛇
      for (int j = 0; j < prev_snakes[i].length; j++) {
        erase_char_at(prev_snakes[i].body[j].x, prev_snakes[i].body[j].y);
      }
    }
  }

  /* ---------- 处理食物变化 ---------- */
  if (prev_food.x != state_data->food.x || prev_food.y != state_data->food.y) {
    // 旧食物位置擦除（如果之前存在）
    if (prev_food.x != -1) {
      erase_char_at(prev_food.x, prev_food.y);
    }
    // 新食物位置绘制
    draw_char_at(state_data->food.x, state_data->food.y, '*');
  }

  /* ---------- 更新右侧玩家信息面板（全量重绘，因为内容较少） ---------- */
  int info_x = INFO_PANEL_X_OFFSET;
  int info_y = map_start_y;
  // 先清除面板区域（用空格覆盖）
  for (int line = 0; line < 10; line++) {
    mvprintw(info_y + line, info_x, "%-40s", "");
  }
  mvprintw(info_y, info_x,
           "=== Players (%d/%d) ===", state_data->active_players,
           state_data->total_players);
  info_y += 2;
  for (int i = 0; i < state_data->player_count; i++) {
    const char *status = state_data->players[i].is_dead ? "DEAD" : "ALIVE";
    const char *you = state_data->players[i].is_you ? " (YOU)" : "";
    mvprintw(info_y + i, info_x, "%s: Score=%d, Len=%d, %s%s",
             state_data->players[i].name, state_data->players[i].score,
             state_data->players[i].length, status, you);
  }

  /* ---------- 更新缓存 ---------- */
  memcpy(prev_snakes, new_snakes, sizeof(SnakeCache) * new_snake_count);
  prev_snake_count = new_snake_count;
  prev_food.x = state_data->food.x;
  prev_food.y = state_data->food.y;

  // 刷新屏幕
  refresh();
}

static int game_loop_ncurses(ClientContext *ctx) {
  nodelay(stdscr, TRUE);

  char buffer[BUFFER_SIZE];
  fd_set read_fds;
  int max_fd = ctx->room_conn->socket_fd;
  if (STDIN_FILENO > max_fd)
    max_fd = STDIN_FILENO;

  int game_active = 1;

  // 进入游戏时重置静态地图标志和缓存
  static_map_drawn = 0;
  prev_snake_count = 0;
  prev_food.x = -1;
  prev_food.y = -1;

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
          receive_message(ctx->room_conn->socket_fd, buffer, sizeof(buffer), 0);
      if (n > 0) {
        buffer[n] = '\0';
        if (strncmp(buffer, "STATE|", 6) == 0) {
          GameStateData state_data;
          if (deserialize_game_state(buffer, &state_data) == 0) {
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
        } else if (strstr(buffer, "Server disconnected") ||
                   strstr(buffer, "shutdown")) {
          clear();
          mvprintw(0, 0, "Room server closed. Returning to room list...");
          refresh();
          napms(2000);
          game_active = 0;
          break;
        } else {
          clear();
          mvprintw(0, 0, "%s", buffer);
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
