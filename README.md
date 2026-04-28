# 🐍 Distributed Snake Game (v0.1)

一个**实验性、过度设计**的分布式贪吃蛇游戏技术原型，用企业级中间件在 Java 里跑起了经典贪吃蛇，核心目标是验证事件驱动架构、ECS 设计模式和高性能序列化在游戏服务器中的可行性。

> ⚠️ **这不是一个现成的可玩游戏**，而是面向 Java 开发者和分布式系统学习者的技术演示项目。

---

## ✨ 技术亮点

- **多协议事件驱动架构**
  游戏内指令通过 **RabbitMQ** 在 Gateway 与 Worker 之间路由；游戏事件（死亡、得分）通过 **Apache Kafka** 发布给排行榜等消费者。两者职责分离，架构更清晰。

- **ECS 架构实验**
  核心逻辑完全基于 **Entity-Component-System** 模式实现（参见 `snake.ecs` 包），包含 `World`、`Entity`、`Component`、`System`，这在 Java 项目中非常罕见。

- **高性能 Disruptor 事件循环**
  每个房间使用 **LMAX Disruptor** 构建单线程事件循环，避免锁竞争，并提供背压处理（满则丢弃并回收消息）。

- **平坦二进制序列化**
  游戏状态同步采用 **FlatBuffers**（零拷贝、无需解析），网关到 Worker 的命令传输使用 **Protocol Buffers**，两者都有实际运用。

- **高可用排行榜设计**
  排行榜分数先写 **Redis（Redisson）**，再异步刷入 MySQL，并配有定时同步机制防止数据不一致。

- **纯异步 Netty 网关**
  基于 **Netty** 的自研二进制/文本混合协议，支持心跳、长连接管理，无 Spring Boot 依赖，启动极快。

---

## 🏗️ 实际架构

```
 客户端 (Swing / 自定义)
       │ TCP (自定义帧协议)
       ▼
    Gateway (Netty)
       │ 命令路由 (RabbitMQ)
       ▼
    Worker (消费队列)
       │ Disruptor RingBuffer
       ▼
    Actor (房间实例, ECS 世界)
       │ 游戏事件 (Kafka)
       ▼
  Leaderboard Consumer (Kafka → Redis + MySQL)
```

**数据流细节**：
1. 客户端发送 `CREATE/JOIN/INPUT` 等 JSON 命令，Gateway 根据玩家位置通过 **RabbitMQ** 转发给对应 Worker。
2. Worker 反序列化 **Protobuf** 格式的命令，投递到房间对应的 **Disruptor RingBuffer**。
3. Actor 每 200ms 一次 tick，执行 ECS 系统（移动、碰撞、食物等），产生 **FlatBuffers** 格式的全量/增量状态，通过 RabbitMQ 推回 Gateway 再发给客户端。
4. 死亡、得分事件通过 **Kafka** 异步发送，独立的 `LeaderboardConsumer` 消费后更新 Redis 排行榜并异步写 MySQL。

---

## 🚀 快速开始

### 前置依赖
- **JDK 17+**
- **Maven 3.8+**
- **RabbitMQ** （Gateway ↔ Worker 通信）
- **Apache Kafka** （游戏事件流）
- **Redis** （排行榜、分布式协调）
- **MySQL** （用户数据、排行榜持久化）

### 1. 启动中间件（使用 Docker 示例）

```bash
# RabbitMQ（管理界面端口 15672）
docker run -d --name snake-mq -p 5672:5672 -p 15672:15672 rabbitmq:management

# Kafka（需要 KRaft 模式，无 ZK）
docker run -d --name snake-kafka -p 9092:9092 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  apache/kafka:latest

# Redis
docker run -d --name snake-redis -p 6379:6379 redis:7

# MySQL（自动创建数据库 snake_game）
docker run -d --name snake-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=snake_game \
  mysql:8
```

### 2. 创建 Kafka Topics（必须）

```bash
/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic game.player.score --partitions 3 --replication-factor 1

/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic game.player.died --partitions 3 --replication-factor 1
```
> 注意：输入命令路由不经过 Kafka，因此不包含 `game.player.input`。

### 3. 数据库初始化

项目启动时 `BaseMySQLRepository` 会自动建表，无需手动执行 SQL。如需手动检查：

```sql
SHOW TABLES; -- 应看到 users 表
DESC users;  -- 包含 high_score 字段
```

### 4. 配置文件

在项目根目录放置 `config.properties`，内容参考：

```properties
# 数据库
db.host=localhost
db.port=3306
db.name=snake_game
db.user=root
db.password=root

# Redis
redis.host=localhost
redis.port=6379

# RabbitMQ
mq.host=localhost
mq.port=5672
mq.username=guest
mq.password=guest

# Kafka
kafka.bootstrap.servers=localhost:9092
kafka.enabled=true
```

### 5. 启动服务（顺序建议）

```bash
# 1. 认证服务（HTTP，默认 19001 端口）
java -cp target/classes:target/dependency/* snake.infrastructure.auth.MainServer

# 2. 排行榜消费者（Kafka → Redis + MySQL）
java -cp ... snake.consumer.LeaderboardConsumer

# 3. Worker（处理游戏逻辑，会自动注册到 Redis）
java -cp ... snake.application.worker.WorkerMain

# 4. Gateway（Netty 端口，默认 8080）
java -cp ... snake.application.gateway.GatewayMain
```

### 6. 客户端

项目提供了 Swing 客户端，启动命令：

```bash
java -cp ... snake.client.swing.GameApp <gateway_ip> 8080
```

---

## 📁 项目包结构（基于源码真实包名）

```
snake.
├── application
│   ├── actor          # GameActor, EventLoop, Scheduler, ECS Tick/Message处理
│   ├── gateway        # Netty 网关、命令分发、心跳、会话管理
│   └── worker         # Worker 主逻辑、消息路由、房间服务
├── consumer           # LeaderboardConsumer（Kafka 消费者）
├── common             # 共享类：Config, Position, Direction, GameStateData, FlatBuffersSerializer
├── distributed         # 基于 Redis 的分布式协调（房间、节点、玩家位置、在线状态、排行榜查询）
├── domain.game        # GameState, GameStateDiff, GameStateDiffer
├── ecs                # ECS 框架（Component, Entity, System, World）
│   ├── components
│   └── systems
├── fbs                # FlatBuffers 生成的 table 类
├── infrastructure
│   ├── auth           # HTTP 认证服务 + 认证客户端 + 密码工具
│   ├── event          # Kafka 事件生产及事件对象（PlayerDied, ScoreChanged）
│   ├── messaging      # RabbitMQ 连接、Worker/Gateway 消息通道
│   └── persistence    # 数据库连接池、用户仓储、排行榜仓储、配置加载
└── client             # Swing 客户端（UI + 网络客户端 + 本地状态缓存）
```

---

## 🔧 工程化待办事项

- [x] **清理 `.gitignore`** – 需排除 `target/`、`logs/`、`*.iml` 等（上一轮已给出）
- [x] **README 更正** – 本文档已根据实际代码重写
- [ ] **自动化 Kafka 主题创建** – 可使用 `KafkaAdmin` 在启动时自动创建，避免手动命令
- [ ] **配置文件外部化** – 当前硬编码了默认值，部分配置散落在 `Config` 类中
- [ ] **序列化统一** – FlatBuffers 和 Protobuf 混用，可考虑统一以降低复杂度
- [ ] **测试覆盖** – 已有部分单元测试，但核心 ECS 和网络层缺少集成测试
- [ ] **构建完善** – 需提供 `pom.xml` 及正确的模块结构，目前只看到零散文件
