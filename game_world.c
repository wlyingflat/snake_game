/**
 * game_world.c - 贪吃蛇游戏世界逻辑
 *
 * 功能：管理游戏房间内的世界状态，包括玩家移动、碰撞检测、食物生成等。
 */

#include "game_world.h"
#include "debug.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ---------------------------- 静态辅助函数声明 ---------------------------- */
static Position find_safe_position(GameManager *manager);
static int check_collision(GameManager *manager, Position pos);
static void respawn_food(GameManager *manager);
static void handle_player_death(Player *p);
static int check_player_collisions(GameManager *manager, Player *p,
                                   Position next, int player_index);
static void update_player_statistics(GameManager *manager);
static void update_shared_memory_data(GameManager *manager);
static Position calculate_next_position(Player *p);
static int is_position_valid(Position pos);
static int is_obstacle_collision(GameManager *manager, Position pos);
static int is_player_collision(GameManager *manager, Position pos,
                               int exclude_player_index);

/* ---------------------------- 公共函数实现 ---------------------------- */

/**
 * 创建游戏管理器
 * @param room_id 房间ID
 * @param shm     共享内存指针
 * @return 成功返回GameManager指针，失败返回NULL
 */
GameManager *game_manager_create(int room_id, SharedMemory *shm) {
  if (room_id < 0 || room_id >= MAX_ROOMS || !shm) {
    ERROR("Invalid parameters to game_manager_create");
    return NULL;
  }

  GameManager *manager = malloc(sizeof(GameManager));
  if (!manager) {
    ERROR("Failed to allocate game manager");
    return NULL;
  }

  manager->room_id = room_id;
  manager->shm = shm;
  manager->last_tick_time = time(NULL);

  // 初始化游戏世界
  memset(&manager->world, 0, sizeof(GameWorld));

  // 初始化读写锁
  pthread_rwlockattr_t attr;
  pthread_rwlockattr_init(&attr);
  pthread_rwlockattr_setpshared(&attr, PTHREAD_PROCESS_SHARED);
  pthread_rwlock_init(&manager->world.lock, &attr);
  pthread_rwlockattr_destroy(&attr);

  manager->world.initial_delay_done = 0;
  manager->world.total_players = 0;
  manager->world.active_players = 0;
  manager->world.should_shutdown = 0;

  // 设置随机种子
  srand(time(NULL) + room_id + getpid());

  // 初始化地图
  game_init_world(manager);

  INFO("Game manager created for room %d", room_id);
  return manager;
}

/**
 * 销毁游戏管理器
 * @param manager 游戏管理器指针
 */
void game_manager_destroy(GameManager *manager) {
  if (!manager)
    return;

  INFO("Destroying game manager for room %d", manager->room_id);

  pthread_rwlock_destroy(&manager->world.lock);
  free(manager);
}

/**
 * 初始化游戏世界（地图、障碍物、食物）
 * @param manager 游戏管理器指针
 */
void game_init_world(GameManager *manager) {
  if (!manager)
    return;

  pthread_rwlock_wrlock(&manager->world.lock);

  memset(manager->world.map, ' ', sizeof(manager->world.map));

  // 绘制边界
  for (int x = 0; x < MAP_WIDTH; x++) {
    manager->world.map[0][x] = '#';
    manager->world.map[MAP_HEIGHT - 1][x] = '#';
  }
  for (int y = 0; y < MAP_HEIGHT; y++) {
    manager->world.map[y][0] = '#';
    manager->world.map[y][MAP_WIDTH - 1] = '#';
  }

  // 生成障碍物
  for (int i = 0; i < OBSTACLE_COUNT; i++) {
    int attempts = 0;
    int placed = 0;

    while (!placed && attempts < MAX_SPAWN_ATTEMPTS) {
      attempts++;

      Position obs = {.x = rand() % (MAP_WIDTH - 4) + 2,
                      .y = rand() % (MAP_HEIGHT - 4) + 2};

      if (manager->world.map[obs.y][obs.x] == ' ') {
        manager->world.obstacles[i] = obs;
        manager->world.map[obs.y][obs.x] = 'X';
        placed = 1;
      }
    }

    if (!placed) {
      WARN("Failed to place obstacle %d after %d attempts", i, attempts);
    }
  }

  // 生成初始食物
  respawn_food(manager);

  pthread_rwlock_unlock(&manager->world.lock);

  DBG("World initialized for room %d with %d obstacles", manager->room_id,
      OBSTACLE_COUNT);
}

/**
 * 更新游戏世界（每 tick 调用一次）
 * @param manager 游戏管理器指针
 */
void game_update_world(GameManager *manager) {
  if (!manager)
    return;

  pthread_rwlock_wrlock(&manager->world.lock);

  if (!manager->world.initial_delay_done) {
    pthread_rwlock_unlock(&manager->world.lock);
    return;
  }

  Position next_heads[MAX_PLAYERS_PER_ROOM];
  int should_die[MAX_PLAYERS_PER_ROOM] = {0};
  int should_grow[MAX_PLAYERS_PER_ROOM] = {0}; // 新增：标记吃到食物

  // 第一阶段：计算移动并标记食物碰撞
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    Player *p = &manager->world.players[i];
    if (!p->is_used || p->is_dead)
      continue;

    next_heads[i] = calculate_next_position(p);

    // 检查是否吃到食物（先于碰撞检测）
    if (next_heads[i].x == manager->world.food.x &&
        next_heads[i].y == manager->world.food.y) {
      should_grow[i] = 1;
    }

    // 检查碰撞（包括新头部是否与自身/其他玩家/障碍物/边界冲突）
    if (check_player_collisions(manager, p, next_heads[i], i))
      should_die[i] = 1;
  }

  // 第二阶段：处理死亡玩家（死亡后不能参与食物处理）
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (should_die[i]) {
      handle_player_death(&manager->world.players[i]);
      should_grow[i] = 0; // 死亡玩家不能吃食物
    }
  }

  // 第三阶段：更新存活玩家位置（区分吃食物和普通移动）
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    Player *p = &manager->world.players[i];
    if (!p->is_used || p->is_dead)
      continue;

    if (should_grow[i]) {
      // 吃到食物：新头部插入，原头部变成身体，尾部不变（长度+1）
      // 需要将原头部往后移，为新头部腾出位置
      if (p->length < MAX_SNAKE_LENGTH) {
        // 将原身体整体后移一位（从尾部开始）
        for (int j = p->length - 1; j >= 0; j--) {
          p->body[j + 1] = p->body[j];
        }
        // 设置新头部
        p->body[0] = next_heads[i];
        p->length++;
        p->score++;
      } else {
        // 达到最大长度，无法再增长，但可以移动（按普通移动处理）
        // 这里简单处理：仍然增长（理论上不可能达到最大值后还能吃到食物）
        for (int j = p->length - 1; j > 0; j--) {
          p->body[j] = p->body[j - 1];
        }
        p->body[0] = next_heads[i];
        p->score++; // 分数仍增加
      }
    } else {
      // 普通移动：新头部插入，尾部移除
      for (int j = p->length - 1; j > 0; j--) {
        p->body[j] = p->body[j - 1];
      }
      p->body[0] = next_heads[i];
    }
  }

  // 第四阶段：重新生成食物（如果有玩家吃到了食物）
  int food_eaten = 0;
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (should_grow[i]) {
      food_eaten = 1;
      break;
    }
  }
  if (food_eaten) {
    respawn_food(manager);
  }

  // 第五阶段：更新统计数据
  update_player_statistics(manager);

  // 第六阶段：检查房间是否需要关闭
  if (manager->world.total_players == 0 && manager->world.initial_delay_done) {
    INFO("Room %d has no connected players, setting shutdown flag",
         manager->room_id);
    manager->world.should_shutdown = 1;
  }

  // 第七阶段：更新共享内存
  update_shared_memory_data(manager);

  pthread_rwlock_unlock(&manager->world.lock);

  DBG("World updated for room %d, active players: %d, total players: %d",
      manager->room_id, manager->world.active_players,
      manager->world.total_players);
}

/**
 * 添加玩家到游戏世界
 * @param manager   游戏管理器指针
 * @param socket_fd 玩家socket
 * @param username  用户名
 * @return 成功返回0，失败返回-1
 */
int game_add_player(GameManager *manager, int socket_fd, const char *username) {
  if (!manager || socket_fd < 0 || !username) {
    ERROR("Invalid parameters to game_add_player");
    return -1;
  }

  pthread_rwlock_wrlock(&manager->world.lock);

  int player_index = -1;
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (!manager->world.players[i].is_used) {
      player_index = i;
      break;
    }
  }

  if (player_index == -1) {
    pthread_rwlock_unlock(&manager->world.lock);
    ERROR("No available player slots in room %d", manager->room_id);
    return -1;
  }

  // 检查玩家是否已存在
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (manager->world.players[i].is_used &&
        strcmp(manager->world.players[i].name, username) == 0) {
      pthread_rwlock_unlock(&manager->world.lock);
      ERROR("Player %s already in room %d", username, manager->room_id);
      return -1;
    }
  }

  Player *player = &manager->world.players[player_index];
  memset(player, 0, sizeof(Player));

  player->is_used = 1;
  player->socket_fd = socket_fd;
  player->player_id = player_index + 1;
  player->length = INIT_SNAKE_LENGTH;
  strncpy(player->name, username, USERNAME_LEN - 1);
  player->name[USERNAME_LEN - 1] = '\0';

  Position spawn_pos = find_safe_position(manager);
  player->body[0] = spawn_pos;

  Direction directions[] = {DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT};
  player->direction = directions[rand() % 4];

  player->score = 0;
  player->is_dead = 0;
  player->join_time = time(NULL);

  manager->world.total_players++;
  manager->world.active_players++;
  manager->world.initial_delay_done = 1;
  manager->world.should_shutdown = 0;

  update_shared_memory_data(manager);

  pthread_rwlock_unlock(&manager->world.lock);

  INFO("Player %s added to room %d at position (%d,%d)", username,
       manager->room_id, spawn_pos.x, spawn_pos.y);
  return 0;
}

/**
 * 从游戏世界移除玩家
 * @param manager   游戏管理器指针
 * @param socket_fd 玩家socket
 * @return 成功返回0，失败返回-1
 */
int game_remove_player(GameManager *manager, int socket_fd) {
  if (!manager || socket_fd < 0) {
    return -1;
  }

  pthread_rwlock_wrlock(&manager->world.lock);

  Player *player = NULL;
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (manager->world.players[i].is_used &&
        manager->world.players[i].socket_fd == socket_fd) {
      player = &manager->world.players[i];
      break;
    }
  }

  if (!player) {
    pthread_rwlock_unlock(&manager->world.lock);
    WARN("Player not found for socket fd %d", socket_fd);
    return -1;
  }

  DBG("Removing player %s from room %d", player->name, manager->room_id);

  player->is_used = 0;
  player->is_dead = 1;

  if (manager->world.total_players > 0)
    manager->world.total_players--;
  if (manager->world.active_players > 0)
    manager->world.active_players--;

  if (manager->world.total_players == 0 && manager->world.initial_delay_done) {
    INFO("Room %d is now empty, setting shutdown flag", manager->room_id);
    manager->world.should_shutdown = 1;
  }

  update_shared_memory_data(manager);

  pthread_rwlock_unlock(&manager->world.lock);

  close(socket_fd);
  return 0;
}

/**
 * 更新玩家移动方向
 * @param manager   游戏管理器指针
 * @param socket_fd 玩家socket
 * @param dir       新方向
 * @return 成功返回0，失败返回-1
 */
int game_update_player_direction(GameManager *manager, int socket_fd,
                                 Direction dir) {
  if (!manager || socket_fd < 0) {
    return -1;
  }

  pthread_rwlock_wrlock(&manager->world.lock);

  Player *player = NULL;
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (manager->world.players[i].is_used &&
        manager->world.players[i].socket_fd == socket_fd &&
        !manager->world.players[i].is_dead) {
      player = &manager->world.players[i];
      break;
    }
  }

  if (!player) {
    pthread_rwlock_unlock(&manager->world.lock);
    return -1;
  }

  player->direction = dir;
  pthread_rwlock_unlock(&manager->world.lock);
  return 0;
}

/**
 * 检查房间是否需要关闭
 * @param manager 游戏管理器指针
 * @return 1需要关闭，0不需要
 */
int game_should_shutdown(GameManager *manager) {
  if (!manager)
    return 0;

  pthread_rwlock_rdlock(&manager->world.lock);
  int should_shutdown = manager->world.should_shutdown;
  pthread_rwlock_unlock(&manager->world.lock);
  return should_shutdown;
}

/**
 * 检查指定位置是否碰撞
 * @param manager 游戏管理器指针
 * @param pos     位置
 * @return 1碰撞，0未碰撞
 */
int game_check_collision(GameManager *manager, Position pos) {
  if (!manager)
    return 1;

  pthread_rwlock_rdlock(&manager->world.lock);
  int collision = check_collision(manager, pos);
  pthread_rwlock_unlock(&manager->world.lock);
  return collision;
}

/**
 * 寻找安全出生位置
 * @param manager 游戏管理器指针
 * @return 安全位置
 */
Position game_find_safe_position(GameManager *manager) {
  Position pos = {0, 0};
  if (!manager)
    return pos;

  pthread_rwlock_rdlock(&manager->world.lock);
  pos = find_safe_position(manager);
  pthread_rwlock_unlock(&manager->world.lock);
  return pos;
}

/**
 * 获取游戏状态数据（用于发送给客户端）
 * @param manager         游戏管理器指针
 * @param state_data      输出状态数据
 * @param player_socket_fd 当前玩家socket（用于标记is_you）
 * @return 成功返回0，失败返回-1
 */
int game_get_state_data(GameManager *manager, GameStateData *state_data,
                        int player_socket_fd) {
  if (!manager || !state_data)
    return -1;

  pthread_rwlock_rdlock(&manager->world.lock);

  state_data->room_id = manager->room_id;
  state_data->food = manager->world.food;
  state_data->obstacle_count = OBSTACLE_COUNT;
  for (int i = 0; i < OBSTACLE_COUNT; i++) {
    state_data->obstacles[i].x = manager->world.obstacles[i].x;
    state_data->obstacles[i].y = manager->world.obstacles[i].y;
  }

  state_data->player_count = 0;
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    Player *p = &manager->world.players[i];
    if (!p->is_used)
      continue;

    strncpy(state_data->players[state_data->player_count].name, p->name,
            USERNAME_LEN - 1);
    state_data->players[state_data->player_count].name[USERNAME_LEN - 1] = '\0';
    state_data->players[state_data->player_count].head = p->body[0];
    state_data->players[state_data->player_count].length = p->length;
    for (int j = 0; j < p->length; j++) {
      state_data->players[state_data->player_count].body[j].x = p->body[j].x;
      state_data->players[state_data->player_count].body[j].y = p->body[j].y;
    }
    state_data->players[state_data->player_count].direction = p->direction;
    state_data->players[state_data->player_count].score = p->score;
    state_data->players[state_data->player_count].is_dead = p->is_dead;
    state_data->players[state_data->player_count].is_you =
        (p->socket_fd == player_socket_fd) ? 1 : 0;

    state_data->player_count++;
  }

  state_data->active_players = manager->world.active_players;
  state_data->total_players = manager->world.total_players;

  pthread_rwlock_unlock(&manager->world.lock);
  return 0;
}

/* ---------------------------- 静态辅助函数实现 ---------------------------- */

/**
 * 计算玩家下一帧头部位置
 * @param p 玩家指针
 * @return 新头部位置
 */
static Position calculate_next_position(Player *p) {
  Position next = p->body[0];
  switch (p->direction) {
  case DIR_UP:
    next.y--;
    break;
  case DIR_DOWN:
    next.y++;
    break;
  case DIR_LEFT:
    next.x--;
    break;
  case DIR_RIGHT:
    next.x++;
    break;
  }
  return next;
}

/**
 * 处理玩家死亡
 * @param p 玩家指针
 */
static void handle_player_death(Player *p) {
  p->is_dead = 1;
  if (p->socket_fd > 0) {
    char death_msg[64];
    snprintf(death_msg, sizeof(death_msg),
             "YOU DIED - Score: %d - Press Q to quit", p->score);
    send(p->socket_fd, death_msg, strlen(death_msg), 0);
  }
}

/**
 * 检查玩家移动是否碰撞
 * @param manager      游戏管理器指针
 * @param p            玩家指针
 * @param next         下一帧头部位置
 * @param player_index 当前玩家索引
 * @return 1碰撞，0安全
 */
static int check_player_collisions(GameManager *manager, Player *p,
                                   Position next, int player_index) {
  if (!is_position_valid(next))
    return 1;
  if (is_obstacle_collision(manager, next))
    return 1;
  for (int j = 0; j < p->length; j++) {
    if (next.x == p->body[j].x && next.y == p->body[j].y)
      return 1;
  }
  if (is_player_collision(manager, next, player_index))
    return 1;
  return 0;
}

/**
 * 更新玩家统计信息
 * @param manager 游戏管理器指针
 */
static void update_player_statistics(GameManager *manager) {
  int active = 0, total = 0;
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    Player *p = &manager->world.players[i];
    if (p->is_used) {
      total++;
      if (!p->is_dead)
        active++;
    }
  }
  manager->world.active_players = active;
  manager->world.total_players = total;
}

/**
 * 更新共享内存中的房间信息
 * @param manager 游戏管理器指针
 */
static void update_shared_memory_data(GameManager *manager) {
  if (manager->shm) {
    shm_lock(manager->shm);
    manager->shm->data->rooms[manager->room_id].player_count =
        manager->world.active_players;
    manager->shm->data->rooms[manager->room_id].last_activity = time(NULL);
    shm_unlock(manager->shm);
  }
}

/**
 * 检查位置是否在地图边界内
 * @param pos 位置
 * @return 1有效，0无效
 */
static int is_position_valid(Position pos) {
  return !(pos.x <= 0 || pos.x >= MAP_WIDTH - 1 || pos.y <= 0 ||
           pos.y >= MAP_HEIGHT - 1);
}

/**
 * 检查位置是否与障碍物碰撞
 * @param manager 游戏管理器指针
 * @param pos     位置
 * @return 1碰撞，0未碰撞
 */
static int is_obstacle_collision(GameManager *manager, Position pos) {
  for (int i = 0; i < OBSTACLE_COUNT; i++) {
    if (pos.x == manager->world.obstacles[i].x &&
        pos.y == manager->world.obstacles[i].y)
      return 1;
  }
  return 0;
}

/**
 * 检查位置是否与其他玩家碰撞
 * @param manager            游戏管理器指针
 * @param pos                位置
 * @param exclude_player_index 排除的玩家索引（自身）
 * @return 1碰撞，0未碰撞
 */
static int is_player_collision(GameManager *manager, Position pos,
                               int exclude_player_index) {
  for (int i = 0; i < MAX_PLAYERS_PER_ROOM; i++) {
    if (i == exclude_player_index)
      continue;
    Player *other = &manager->world.players[i];
    if (!other->is_used || other->is_dead)
      continue;
    for (int j = 0; j < other->length; j++) {
      if (pos.x == other->body[j].x && pos.y == other->body[j].y)
        return 1;
    }
  }
  return 0;
}

/**
 * 寻找安全位置（不被任何物体占据）
 * @param manager 游戏管理器指针
 * @return 安全位置
 */
static Position find_safe_position(GameManager *manager) {
  Position pos;
  int attempts = 0;
  do {
    pos.x = rand() % (MAP_WIDTH - 2) + 1;
    pos.y = rand() % (MAP_HEIGHT - 2) + 1;
    attempts++;
    if (attempts > MAX_SPAWN_ATTEMPTS) {
      WARN("Could not find safe position after %d attempts", attempts);
      pos.x = MAP_WIDTH / 2;
      pos.y = MAP_HEIGHT / 2;
      break;
    }
  } while (check_collision(manager, pos));

  DBG("Found safe position at (%d,%d) after %d attempts", pos.x, pos.y,
      attempts);
  return pos;
}

/**
 * 综合碰撞检查（包括边界、障碍物、食物、其他玩家）
 * @param manager 游戏管理器指针
 * @param pos     位置
 * @return 1碰撞，0安全
 */
static int check_collision(GameManager *manager, Position pos) {
  return !is_position_valid(pos) || is_obstacle_collision(manager, pos) ||
         (pos.x == manager->world.food.x && pos.y == manager->world.food.y) ||
         is_player_collision(manager, pos, -1);
}

/**
 * 重新生成食物
 * @param manager 游戏管理器指针
 */
static void respawn_food(GameManager *manager) {
  manager->world.food = find_safe_position(manager);
  DBG("Food respawned at (%d,%d)", manager->world.food.x,
      manager->world.food.y);
}
