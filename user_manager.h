/**
 * user_manager.h - 用户管理器接口
 *
 * 功能：声明用户管理相关函数。
 */

#ifndef USER_MANAGER_H
#define USER_MANAGER_H

#include "common.h"
#include "shm_manager.h"

// 用户管理
typedef struct {
  const char *filename;
  SharedMemory *shm;
} UserManager;

// 初始化/销毁
UserManager *user_manager_create(const char *filename, SharedMemory *shm);
void user_manager_destroy(UserManager *manager);

// 用户操作
int user_register(UserManager *manager, const char *username,
                  const char *password);
int user_login(UserManager *manager, const char *username,
               const char *password);
int user_logout(UserManager *manager, const char *username);
int user_is_online(UserManager *manager, const char *username);

// 密码处理
void password_hash(const char *password,
                   unsigned char hash[SHA256_DIGEST_LENGTH]);
int password_verify(const char *password,
                    const unsigned char expected_hash[SHA256_DIGEST_LENGTH]);

#endif // USER_MANAGER_H
