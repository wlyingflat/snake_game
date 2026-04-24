# Makefile for Snake Game

CC = gcc
CFLAGS = -Wall -Wextra -O2 -g -I. -Wno-stringop-truncation
LDFLAGS = -lpthread -lcrypto -lncurses

# 目录结构
BIN_DIR = bin
OBJ_DIR = obj

# 目标文件
TARGETS = $(BIN_DIR)/main_server $(BIN_DIR)/room_server $(BIN_DIR)/client $(BIN_DIR)/gateway

# 公共库文件
COMMON_SRCS = shm_manager.c \
              threadpool.c \
              user_manager.c \
              room_manager.c \
              game_world.c \
              network.c

# 获取公共库的对象文件
COMMON_OBJS = $(patsubst %.c,$(OBJ_DIR)/%.o,$(COMMON_SRCS))

# 主服务器文件
MAIN_SERVER_SRC = main_server.c
MAIN_SERVER_OBJ = $(OBJ_DIR)/main_server.o

# 房间服务器文件
ROOM_SERVER_SRC = room_server.c
ROOM_SERVER_OBJ = $(OBJ_DIR)/room_server.o

# 客户端文件
CLIENT_SRC = client.c
CLIENT_OBJ = $(OBJ_DIR)/client.o

# 网关文件
GATEWAY_SRC = gateway.c
GATEWAY_OBJ = $(OBJ_DIR)/gateway.o

# 默认目标
all: directories $(TARGETS)

# 创建目录
directories:
	mkdir -p $(BIN_DIR) $(OBJ_DIR)

# 编译公共库文件
$(OBJ_DIR)/%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

# 编译主服务器
$(BIN_DIR)/main_server: $(MAIN_SERVER_OBJ) $(COMMON_OBJS)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

# 编译房间服务器
$(BIN_DIR)/room_server: $(ROOM_SERVER_OBJ) $(COMMON_OBJS)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

# 编译客户端
$(BIN_DIR)/client: $(CLIENT_OBJ) $(COMMON_OBJS)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

# 编译网关
$(BIN_DIR)/gateway: $(GATEWAY_OBJ) $(COMMON_OBJS)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

# 清理
clean:
	rm -rf $(BIN_DIR)/* $(OBJ_DIR)/*

# 运行测试
test-server: all
	$(BIN_DIR)/main_server 8888

test-room: all
	$(BIN_DIR)/room_server 0 20000

test-client: all
	$(BIN_DIR)/client 127.0.0.1 8888

# 安装依赖（Ubuntu/Debian）
install-deps:
	sudo apt-get update
	sudo apt-get install -y libncurses5-dev libssl-dev

# 调试信息
debug:
	@echo "Common objects: $(COMMON_OBJS)"
	@echo "Common sources: $(COMMON_SRCS)"

.PHONY: all clean directories test-server test-room test-client install-deps debug
