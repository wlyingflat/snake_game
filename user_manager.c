/**
 * user_manager.c - 用户管理器（优化精简版）
 *
 * 功能：管理用户注册、登录、登出，密码加盐哈希存储，与共享内存交互。
 */

#include "user_manager.h"
#include "debug.h"
#include <errno.h>
#include <fcntl.h>
#include <openssl/sha.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ---------------------------- 静态函数声明 ---------------------------- */
static int save_users_to_file(UserManager *manager);
static int load_users_from_file(UserManager *manager);
static uint32_t generate_salt(void);
static void salted_password_hash(const char *password, uint32_t salt,
                                 unsigned char hash[SHA256_DIGEST_LENGTH]);
static User *find_user_locked(SharedMemory *shm, const char *username);

/* ---------------------------- 函数实现 ---------------------------- */

/**
 * 生成随机盐
 */
static uint32_t generate_salt(void) {
  static int seeded = 0;
  if (!seeded) {
    srand(time(NULL) ^ getpid());
    seeded = 1;
  }
  return (uint32_t)rand();
}

/**
 * 加盐密码哈希
 */
static void salted_password_hash(const char *password, uint32_t salt,
                                 unsigned char hash[SHA256_DIGEST_LENGTH]) {
  char salted[USERNAME_LEN + 32];
  snprintf(salted, sizeof(salted), "%s%08x", password, salt);
  SHA256((unsigned char *)salted, strlen(salted), hash);
}

/**
 * 在共享内存中查找用户（调用者必须已加锁）
 */
static User *find_user_locked(SharedMemory *shm, const char *username) {
  for (int i = 0; i < shm->data->user_count; i++) {
    if (strcmp(shm->data->users[i].name, username) == 0)
      return &shm->data->users[i];
  }
  return NULL;
}

/**
 * 创建用户管理器
 */
UserManager *user_manager_create(const char *filename, SharedMemory *shm) {
  if (!filename || !shm) {
    ERROR("Invalid parameters to user_manager_create");
    return NULL;
  }

  UserManager *manager = malloc(sizeof(UserManager));
  if (!manager) {
    ERROR("Failed to allocate user manager");
    return NULL;
  }

  manager->filename = strdup(filename);
  if (!manager->filename) {
    ERROR("Failed to allocate filename");
    free(manager);
    return NULL;
  }

  manager->shm = shm;

  if (load_users_from_file(manager) < 0) {
    WARN("Failed to load users from file, starting with empty user list");
    shm_lock(shm);
    shm->data->user_count = 0;
    shm_unlock(shm);
  }

  INFO("User manager created, loaded %d users from %s", shm->data->user_count,
       filename);
  return manager;
}

/**
 * 销毁用户管理器（保存数据到文件）
 */
void user_manager_destroy(UserManager *manager) {
  if (!manager)
    return;
  save_users_to_file(manager);
  free((void *)manager->filename);
  free(manager);
  INFO("User manager destroyed");
}

/**
 * 用户注册
 */
int user_register(UserManager *manager, const char *username,
                  const char *password) {
  if (!manager || !username || !password) {
    ERROR("Invalid parameters to user_register");
    return 0;
  }

  if (strlen(username) == 0 || strlen(username) >= USERNAME_LEN) {
    ERROR("Invalid username length");
    return 0;
  }
  if (strlen(password) == 0) {
    ERROR("Password cannot be empty");
    return 0;
  }

  DBG("Registering user: %s", username);
  shm_lock(manager->shm);

  if (find_user_locked(manager->shm, username)) {
    shm_unlock(manager->shm);
    ERROR("User %s already exists", username);
    return 0;
  }

  if (manager->shm->data->user_count >= MAX_USERS) {
    shm_unlock(manager->shm);
    ERROR("Maximum user limit reached (%d)", MAX_USERS);
    return 0;
  }

  User *new_user = &manager->shm->data->users[manager->shm->data->user_count];
  memset(new_user, 0, sizeof(User));
  strncpy(new_user->name, username, USERNAME_LEN - 1);
  new_user->salt = generate_salt();
  salted_password_hash(password, new_user->salt, new_user->password_hash);
  new_user->online = 1;
  new_user->last_active = time(NULL);

  manager->shm->data->user_count++;
  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  if (save_users_to_file(manager) < 0)
    WARN("Failed to save user to file, but user registered in memory");

  INFO("User %s registered successfully", username);
  return 1;
}

/**
 * 用户登录
 */
int user_login(UserManager *manager, const char *username,
               const char *password) {
  if (!manager || !username || !password) {
    ERROR("Invalid parameters to user_login");
    return 0;
  }

  DBG("User login attempt: %s", username);
  shm_lock(manager->shm);

  User *user = find_user_locked(manager->shm, username);
  if (!user) {
    shm_unlock(manager->shm);
    ERROR("User %s not found", username);
    return 0;
  }

  unsigned char test_hash[SHA256_DIGEST_LENGTH];
  salted_password_hash(password, user->salt, test_hash);
  if (memcmp(test_hash, user->password_hash, SHA256_DIGEST_LENGTH) != 0) {
    shm_unlock(manager->shm);
    ERROR("Incorrect password for user %s", username);
    return 0;
  }

  if (user->online) {
    shm_unlock(manager->shm);
    ERROR("User %s already logged in", username);
    return 0;
  }

  user->online = 1;
  user->last_active = time(NULL);
  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  if (save_users_to_file(manager) < 0)
    WARN("Failed to save user login state to file for user %s", username);

  INFO("User %s logged in successfully", username);
  return 1;
}

/**
 * 用户登出
 */
int user_logout(UserManager *manager, const char *username) {
  if (!manager || !username) {
    ERROR("Invalid parameters to user_logout");
    return 0;
  }

  DBG("User logout: %s", username);
  shm_lock(manager->shm);

  User *user = find_user_locked(manager->shm, username);
  if (!user) {
    shm_unlock(manager->shm);
    WARN("User %s not found for logout", username);
    return 0;
  }

  user->online = 0;
  user->last_active = time(NULL);
  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  INFO("User %s logged out", username);
  return 1;
}

/**
 * 检查用户是否在线
 */
int user_is_online(UserManager *manager, const char *username) {
  if (!manager || !username)
    return 0;

  shm_lock(manager->shm);
  User *user = find_user_locked(manager->shm, username);
  int online = user ? user->online : 0;
  shm_unlock(manager->shm);
  return online;
}

/* ---------------------------- 文件操作 ---------------------------- */

/**
 * 保存用户数据到文件
 */
static int save_users_to_file(UserManager *manager) {
  if (!manager || !manager->filename)
    return -1;

  char temp_filename[256];
  snprintf(temp_filename, sizeof(temp_filename), "%s.tmp", manager->filename);

  FILE *file = fopen(temp_filename, "w");
  if (!file) {
    ERROR("Failed to open temporary file %s: %s", temp_filename,
          strerror(errno));
    return -1;
  }

  shm_lock(manager->shm);

  for (int i = 0; i < manager->shm->data->user_count; i++) {
    User *user = &manager->shm->data->users[i];
    fprintf(file, "%s %08x ", user->name, user->salt);
    for (int j = 0; j < SHA256_DIGEST_LENGTH; j++)
      fprintf(file, "%02x", user->password_hash[j]);
    fprintf(file, " %d %ld\n", user->online, (long)user->last_active);
  }

  shm_unlock(manager->shm);
  fclose(file);

  if (rename(temp_filename, manager->filename) < 0) {
    ERROR("Failed to rename temporary file: %s", strerror(errno));
    unlink(temp_filename);
    return -1;
  }

  DBG("Saved %d users to %s", manager->shm->data->user_count,
      manager->filename);
  return 0;
}

/**
 * 从文件加载用户数据（覆盖模式，清空原有用户）
 */
static int load_users_from_file(UserManager *manager) {
  if (!manager || !manager->filename)
    return -1;

  FILE *file = fopen(manager->filename, "r");
  if (!file) {
    if (errno == ENOENT)
      return 0; // 文件不存在，视为空
    ERROR("Failed to open user file %s: %s", manager->filename,
          strerror(errno));
    return -1;
  }

  // 临时存储从文件读取的用户
  User temp_users[MAX_USERS];
  int temp_count = 0;
  char line[512];

  while (fgets(line, sizeof(line), file)) {
    if (line[0] == '\n' || line[0] == '#')
      continue;

    char username[USERNAME_LEN];
    unsigned int salt;
    char hash_hex[SHA256_DIGEST_LENGTH * 2 + 1];
    int online; // 文件中的在线状态忽略，强制设为离线
    long last_active;

    if (sscanf(line, "%31s %8x %64s %d %ld", username, &salt, hash_hex, &online,
               &last_active) != 5) {
      WARN("Invalid line in user file: %s", line);
      continue;
    }

    if (temp_count >= MAX_USERS) {
      WARN("User limit reached, stopping load");
      break;
    }

    User *u = &temp_users[temp_count];
    memset(u, 0, sizeof(User));
    strncpy(u->name, username, USERNAME_LEN);
    u->name[USERNAME_LEN - 1] = '\0';
    u->salt = salt;

    for (int i = 0; i < SHA256_DIGEST_LENGTH; i++)
      sscanf(hash_hex + 2 * i, "%2hhx", &u->password_hash[i]);

    // 强制设置为离线状态
    u->online = 0;
    u->last_active = time(NULL);

    temp_count++;
  }
  fclose(file);

  // 用临时数组覆盖共享内存中的用户数据
  shm_lock(manager->shm);

  // 清空原有用户
  manager->shm->data->user_count = 0;

  for (int i = 0; i < temp_count; i++) {
    manager->shm->data->users[i] = temp_users[i];
    manager->shm->data->user_count++;
  }

  manager->shm->data->last_updated = time(NULL);
  shm_unlock(manager->shm);

  INFO("Loaded %d users from %s (overwrite mode)", temp_count,
       manager->filename);
  return temp_count;
}
