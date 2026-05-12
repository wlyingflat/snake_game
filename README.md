```markdown
# Snake Game – 分布式多人在线吞噬游戏

一款基于 Java 17 实现的高性能、分布式多人在线游戏服务端与客户端。游戏玩法类似 Agar.io，支持数百名玩家同时在线对战、房间创建/加入、排行榜、认证等功能。系统采用 **Actor 模型 + ECS 架构**，通过 Disruptor 事件循环实现高速消息处理，使用 Netty 搭建网关，RabbitMQ 进行跨服务通信，Redis 实现分布式协调，Kafka 输出游戏事件，MySQL 持久化用户与排行榜数据。

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [项目结构](#项目结构)
- [环境依赖](#环境依赖)
- [快速开始](#快速开始)
  - [1. 准备基础设施](#1-准备基础设施)
  - [2. 配置文件](#2-配置文件)
  - [3. 构建项目](#3-构建项目)
  - [4. 启动服务](#4-启动服务)
  - [5. 启动客户端](#5-启动客户端)
- [组件说明](#组件说明)
- [游戏协议](#游戏协议)
- [测试与压测](#测试与压测)
- [配置参考](#配置参考)
- [许可证](#许可证)

---

## 功能特性

- **吞噬游戏逻辑**：玩家控制细胞移动、分裂、弹出质量，吞噬食物与其他玩家细胞。
- **ECS 架构**：游戏核心采用 Entity-Component-System 模式，逻辑清晰，易于扩展。
- **多房间支持**：多个房间可以并行运行，每个房间由独立的 Actor 管理。
- **分布式部署**：支持水平扩展 Worker 节点，通过 Redis 实现房间与玩家分布。
- **高性能网关**：Netty 实现 TCP 网关，支持自定义二进制与文本混合协议，心跳保活。
- **消息总栈**：基于 RabbitMQ 实现 Gateway ↔ Worker 异步通信，以及房间列表广播。
- **事件驱动**：通过 Kafka 发布玩家死亡、分数变化等事件，独立消费端维护排行榜。
- **认证系统**：基于 Jetty 的 HTTP 认证服务，支持注册、登录、登出。
- **客户端**：提供 Java Swing 桌面客户端，实时渲染游戏帧，以及压测客户端和协议测试工具。
- **监控与日志**：Log4j2 日志输出，可配置各级别日志。

---

## 技术栈

| 类别       | 技术/框架                                          |
| ---------- | -------------------------------------------------- |
| 语言       | Java 17                                            |
| 网络框架   | Netty 4.1 (Epoll/NIO)                              |
| 消息队列   | RabbitMQ (AMQP 5.x)                                |
| 分布式协调 | Redisson (Redis)                                   |
| 事件流     | Apache Kafka                                       |
| 数据库     | MySQL 8 + HikariCP 连接池                           |
| 序列化     | FlatBuffers, Protobuf, Jackson                     |
| 并发框架   | LMAX Disruptor                                     |
| 认证服务   | Jetty 11 + Servlet                                  |
| 日志       | Log4j2 + SLF4J                                     |
| 客户端     | Java Swing + FlatBuffers                           |
| 构建工具   | Maven + Protobuf Maven Plugin                      |

---

## 系统架构

```
 ┌─────────┐   TCP + 心跳    ┌─────────────┐   RabbitMQ    ┌─────────────┐
 │  Client  │<──────────────>│   Gateway    │<─────────────>│   Worker     │
 │ (Swing)  │   JSON/FB帧     │   (Netty)    │  Protobuf     │  (Actor ECS)│
 └─────────┘                  └──────┬───────┘               └──────┬───────┘
                                     │                              │
                                     │ Redis                       │ Kafka
                                     ▼                              ▼
                              ┌─────────────┐               ┌──────────────┐
                              │    Redis     │               │    Kafka      │
                              │  (Room/在线) │               │ (Died/Score) │
                              └─────────────┘               └──────┬───────┘
                                                                   │
                                                                   ▼
                                                            ┌──────────────┐
                                                            │ Leaderboard  │
                                                            │ Consumer     │
                                                            └──────┬───────┘
                                                                   │
                                                                   ▼
                                                            ┌──────────────┐
                                                            │    MySQL      │
                                                            │  (用户/排行)  │
                                                            └──────────────┘
```

- **Gateway** 负责接入客户端、协议解码、认证转发、房间列表推送。
- **Worker** 运行游戏房间实例，每个房间使用独立的 Disruptor 事件循环处理 Tick 和玩家指令。
- **Redis** 存储房间分配、玩家位置、在线状态、排行榜缓存。
- **RabbitMQ** 解耦 Gateway 与 Worker，并广播房间列表更新。
- **Kafka** 异步发布游戏事件，由独立进程消费并写入 MySQL 排行榜。
- **Auth HTTP Server** 提供注册/登录/登出 REST API，Gateway 通过 HTTP 客户端验证用户。

---

## 项目结构

```
src/main/java/snake
├── application
│   ├── actor         # Actor 模型：事件循环、调度器、消息处理器、游戏 Tick
│   ├── gateway       # 网关实现：Netty 服务器、命令分发、心跳、会话管理
│   └── worker        # Worker 服务：接收 RabbitMQ 消息，管理游戏房间
├── benchmark/client  # 压测客户端
├── client            # 终端客户端库（Socket 通信、帧解析）
│   └── swing         # Swing 桌面 GUI 客户端
│   └── tester        # 协议测试工具
├── common            # 公共类：配置、日志、位置、Json 工具等
├── consumer          # 排行榜 Kafka 消费者
├── distributed       # Redis 分布式协调服务
├── domain/game       # 游戏领域定义：消息、状态接口
├── ecs               # 轻量 ECS 框架
│   ├── components    # 组件定义（位置、质量、所有权等）
│   └── systems       # 系统：移动、碰撞、分裂、弹射、食物生成等
├── infrastructure    # 基础设施层
│   ├── auth          # 认证服务与客户端
│   ├── event         # Kafka 事件定义与生产者
│   ├── messaging     # RabbitMQ 消息通道封装
│   └── persistence   # MySQL、Redis 仓储实现、键名常量
├── fbs               # FlatBuffers 生成的 Java 类
└── messaging         # Protobuf 生成的 CommandMsg 类
```

---

## 环境依赖

- **JDK 17** 或更高版本
- **Maven 3.6+**
- **MySQL 8.0** （数据库 `snake_game` 会自动建表）
- **Redis** 7+ （分布式模式必需）
- **RabbitMQ** 3.9+ （必须）
- **Kafka** 3.0+ （可选，若 `kafka.enabled=false` 可关闭）

### 操作系统

开发与测试在 Linux/macOS 下进行，Windows 亦可运行，但建议使用 Linux 服务器部署以获得最佳 Epoll 性能。

---

## 快速开始

### 1. 准备基础设施

确保以下服务已启动并可通过默认端口访问：

- **MySQL**：`localhost:3306`，创建数据库 `snake_game`（或通过 `config.properties` 修改）
- **Redis**：`localhost:6379`
- **RabbitMQ**：`localhost:5672`，管理界面 `15672`
- **Kafka**：`localhost:9092`（若不使用排行榜消费者，可暂时不启动）

### 2. 配置文件

编辑 `src/main/resources/config.properties` （或项目根目录），主要配置项：

```properties
# 数据库连接
db.host=localhost
db.port=3306
db.name=snake_game
db.user=root
db.password=your_password
db.pool.size=10

# Redis
redis.host=localhost
redis.port=6379

# RabbitMQ
mq.host=localhost
mq.port=5672
mq.username=guest
mq.password=guest

# Kafka (若 enabled=false 则关闭)
kafka.bootstrap.servers=localhost:9092
kafka.enabled=true

# 分布式模式
distributed.mode=true
node.id=worker-1

# 游戏参数
tick.interval.ms=200
max.players.per.room=200
room.idle.timeout=30
```

**注意**：日志配置文件 `log4j2.xml` 位于 `src/main/resources/`，可按需调整输出级别。

### 3. 构建项目

在项目根目录执行：

```bash
mvn clean compile
```

若需要打包运行，可执行 `mvn package -DskipTests`，生成的 jar 位于 `target/`。

### 4. 启动服务

建议按以下顺序启动各个组件：

#### 4.1 认证服务（Auth HTTP Server）

```bash
java -cp target/classes:target/dependency/* snake.infrastructure.auth.MainServer
```

默认监听端口 `19001`，可在 `config.properties` 中修改。

#### 4.2 排行榜消费者（可选，依赖 Kafka）

```bash
java -cp target/classes:target/dependency/* snake.consumer.LeaderboardConsumer
```

该进程会消费 Kafka 上的 `game.player.died` 事件，更新 MySQL 和 Redis 排行榜。

#### 4.3 Worker 服务

```bash
java -Dnode.id=worker-1 -cp target/classes:target/dependency/* snake.application.worker.WorkerMain
```

可启动多个 Worker 实例，每个需设置不同的 `node.id`。分布式模式下 Worker 会自动注册到 Redis。

#### 4.4 Gateway 服务

```bash
java -Dnode.id=gateway-1 -Ddistributed.mode=true -cp target/classes:target/dependency/* snake.application.gateway.GatewayMain 8080
```

参数 `8080` 为监听端口，可省略，默认为 `8080`。

### 5. 启动客户端

**Swing 图形客户端**：

```bash
java -cp target/classes:target/dependency/* snake.client.swing.GameApp <gateway_ip> <gateway_port>
```

**协议测试工具**：

```bash
java -cp target/classes:target/dependency/* snake.client.tester.ProtocolTester <gateway_ip> <port> <username> <password>
```

**压测客户端**（不带图形界面）：

```bash
java -cp target/classes:target/dependency/* snake.benchmark.client.GameLoadTestClient <host> <port> <clients> <duration_seconds>
```

---

## 组件说明

### Gateway
- 基于 Netty 的高性能 TCP 服务器，支持 Epoll（Linux）和 NIO 回退。
- 使用 **LengthFieldBasedFrameDecoder** 处理粘包，**ProtocolFrameDecoder** 区分文本（JSON）和二进制（FlatBuffers）帧。
- 内置心跳机制（Ping/Pong），超时自动断开，并与 Redis 在线状态同步。
- 通过 RabbitMQ 将玩家指令转发至对应 Worker，并订阅面向特定玩家的消息。

### Worker
- 每个房间由 `GameActor` 管理，内部使用 **Disruptor** 单线程事件循环处理 Tick 和玩家命令。
- 游戏逻辑基于 **ECS** 实现：`MovementSystem`、`CollisionSystem`、`SplitExecutionSystem`、`EjectSystem` 等。
- 使用 `FlatBuffersSerializer` 将游戏状态序列化为二进制帧，通过 RabbitMQ 广播给玩家所在 Gateway。
- Tick 调度由 `ActorScheduler` 管理，空闲房间自动销毁。

### Actor 模型
- `ActorEventLoop` 封装了 Disruptor，提供背压处理（RingBuffer 满时丢弃并回收对象）。
- 消息分为 `TickMessage` 和 `EnhancedMessage`（包含业务指令）。EnhancedMessage 使用 Netty `Recycler` 对象池减少 GC。

### 分布式协调
- **RoomRepository**：房间创建、分配 Worker、状态更新。
- **PlayerLocationRepository**：记录玩家所在 Gateway 和房间。
- **OnlineStatusService**：在线心跳与超时检测。
- **LeaderboardService**：查询 Redis Sorted Set 排行榜。

### 事件系统
- `KafkaEventProducer` 异步发送 `PlayerDiedEvent`、`ScoreChangedEvent`。
- `LeaderboardConsumer` 消费死亡事件，实时更新 Redis 并异步写入 MySQL，启动时全量同步。

---

## 游戏协议

网关与客户端之间采用 **长度前缀 + 类型标识 + 负载** 的自定义帧格式：

```
[4 字节 Big-Endian 长度] [1 字节类型] [负载数据]
```

- 长度 = 类型字节长度 + 负载长度
- 类型 `0x01` 表示 UTF-8 JSON 文本，`0x00` 表示 FlatBuffers 二进制。

**文本命令示例（JSON）**：

```json
{"cmd":"LOGIN","username":"player1","password":"pass"}
{"cmd":"MOVE","x":1200.0,"y":800.0}
{"cmd":"SPLIT","x":1300.0,"y":850.0}
{"cmd":"EJECT","x":1400.0,"y":900.0}
```

**服务器推送的二进制帧**：使用 FlatBuffers 的 `AgarFrame` 结构，包含所有球状态和食物位置。

详细 FlatBuffers Schema 见 `src/main/flatbuffers/` 目录（根据代码推断，未提供源文件，但生成代码在 `snake.fbs` 包中）。

---

## 测试与压测

### 单元测试

项目包含基于 JUnit 5 和 Mockito 的测试用例，运行：

```bash
mvn test
```

### 压力测试

使用 `GameLoadTestClient` 模拟大量并发客户端：

```bash
java -cp target/classes:target/dependency/* snake.benchmark.client.GameLoadTestClient 127.0.0.1 8080 500 60
```

参数：IP、端口、并发数、持续时间（秒）。客户端会模拟登录、随机移动、分裂、弹射等操作。

### 协议测试

`ProtocolTester` 可用于逐条验证命令交互：

```bash
java -cp target/classes:target/dependency/* snake.client.tester.ProtocolTester 127.0.0.1 8080 testuser password
```

---

## 配置参考

所有可配置项位于 `config.properties` 及系统属性（`-D` 参数）中。部分关键属性：

| 属性                       | 默认值               | 说明                       |
| -------------------------- | -------------------- | -------------------------- |
| `tick.interval.ms`         | 200                  | 游戏 Tick 周期（毫秒）     |
| `max.players.per.room`     | 200                  | 每个房间最大玩家数         |
| `room.idle.timeout`        | 30                   | 房间空闲超时（秒）         |
| `ring.buffer.size`         | 1024                 | Disruptor RingBuffer 大小  |
| `heartbeat.interval`       | 30                   | 心跳间隔（秒）             |
| `heartbeat.timeout`        | 60                   | 心跳超时（秒）             |
| `distributed.mode`         | true                 | 是否启用分布式模式（Redis）|
| `kafka.enabled`            | true                 | 是否启用 Kafka 事件发送    |
| `auth.service.url`         | http://127.0.0.1:19001 | 认证服务 URL              |

更多配置见 `snake.common.Config` 类。

---

## 许可证

本项目仅用于学习与演示，保留所有权利。若需使用，请遵守相关组件（Netty、Disruptor、Redisson 等）的原始许可证。
