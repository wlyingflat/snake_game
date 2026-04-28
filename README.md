# 🐍 Distributed Snake Game (v0.1)

一个**实验性、过度设计**的分布式贪吃蛇游戏技术原型，用企业级中间件在 Java 里跑起了经典贪吃蛇，核心目标是验证事件驱动架构、ECS 设计模式和高性能序列化在游戏服务器中的可行性。

> ⚠️ **这不是一个现成的可玩游戏**，而是面向 Java 开发者和分布式系统学习者的技术演示项目。

---

## ✨ 技术亮点

- **事件驱动 + 分布式微服务**
  游戏逻辑被拆分到 **Gateway**、**Worker**、**Actor** 等独立角色中，通过 **Apache Kafka** 作为分布式消息中枢，实现解耦和横向扩展。

- **ECS 架构实验**
  代码中引入了游戏引擎常用的 **Entity-Component-System (ECS)** 模式（位于 `snake.ecs` 包），在典型 Java 业务应用里非常少见。

- **高性能排行榜设计**
  采用 **Redis（Redisson）+ MySQL** 的读写分离策略：排行榜分数先写 Redis，再异步刷入数据库，保证高并发下的性能与最终一致性。

- **双重序列化方案**
  项目中同时实践了 **FlatBuffers** 与 **Protocol Buffers**，适合对比两者在性能、内存占用和易用性上的差异。

- **Spring Boot + 主流中间件**
  Spring Boot 整合 Kafka、Redis、MySQL 等，展示企业级基础设施的典型组合。

---

## 🏗️ 架构概览

```
         玩家浏览器 / 客户端
               │
               ▼
          ┌─────────┐
          │ Gateway │  ← 接收玩家输入，发布到 Kafka
          └────┬────┘
               │
          ┌────▼────────────────────────┐
          │   Apache Kafka (消息总线)     │
          │  Topics:                     │
          │  - game.player.input         │
          │  - game.player.score         │
          │  - game.player.died          │
          └────┬──────────┬──────────────┘
               │          │
          ┌────▼───┐ ┌───▼─────────┐
          │ Worker │ │ Leaderboard │
          └────┬───┘ │ (Redis+DB)  │
               │      └──────────────┘
          ┌────▼────┐
          │  Actor   │  ← 持有游戏状态，运行 ECS 逻辑
          └─────────┘
```

**数据流说明：**
1. **玩家输入** → Gateway 发布 `game.player.input` 消息。
2. **Worker** 消费输入事件，分发给对应的 **Actor**。
3. **Actor** 更新 ECS 组件状态，计算碰撞、得分等。
4. 得分事件写入 `game.player.score`，死亡事件写入 `game.player.died`。
5. **排行榜服务** 消费这些事件，更新 Redis 排行榜，并异步持久化到 MySQL。

---

## 🚀 快速开始

### 前置依赖
- **JDK 17+**
- **Maven 3.8+**
- **Apache Kafka** (建议本地开发使用 Docker)
- **Redis** (6.x+，推荐使用 Redisson 客户端)
- **MySQL** (5.7+ 或 8.0)

### 1. 启动中间件
如果你使用 Docker，可以快速准备环境：

```bash
# 启动 Kafka (Confluent 快速启动镜像)
docker run -d --name snake-kafka -p 9092:9092 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 apache/kafka:latest

# 启动 Redis
docker run -d --name snake-redis -p 6379:6379 redis:7

# 启动 MySQL
docker run -d --name snake-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=snake mysql:8
```

### 2. 创建 Kafka Topics（必须）
项目默认不会自动创建主题，你需要手动执行以下命令：

```bash
# 如果 Kafka 安装在 /usr/share/kafka 下
/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic game.player.input --partitions 3 --replication-factor 1

/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic game.player.score --partitions 3 --replication-factor 1

/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic game.player.died --partitions 3 --replication-factor 1
```

> 💡 **进阶技巧**：你可以在代码里通过 `KafkaAdmin` + `NewTopic` Bean 实现自动创建，避免手动执行脚本。见后文“待办事项”。

### 3. 数据库初始化
在 MySQL 中创建对应的库表（示例）：

```sql
CREATE DATABASE IF NOT EXISTS snake;
USE snake;
CREATE TABLE leaderboard (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id VARCHAR(64) NOT NULL,
  score INT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 4. 配置文件
修改各模块的 `application.yml`（或 `application.properties`），指向你本地的 Kafka、Redis 和 MySQL 地址。例如：

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
  redis:
    host: localhost
    port: 6379
  datasource:
    url: jdbc:mysql://localhost:3306/snake
    username: root
    password: root
```

### 5. 编译与启动
项目拆分为多个模块，你可以逐个启动：

```bash
mvn clean package -DskipTests
# 按需启动各个服务，例如：
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
java -jar worker/target/worker-0.0.1-SNAPSHOT.jar
# ... 以此类推
```

### 6. 验证
启动后观察控制台日志，如果 Kafka 连接正常、主题无报错，服务即可开始工作。目前没有前端界面，建议通过日志或 Kafka 消费工具（如 `kafka-console-consumer`）查看消息流转。

---

## 📁 项目结构（部分）

```
snake_game/
├── snake-ecs/           # ECS 核心抽象与实现
├── snake-proto/         # Protobuf / FlatBuffers 定义与生成代码
├── gateway/             # 网关服务：接收玩家连接和输入
├── worker/              # 工作节点：消费 Kafka 事件，调度Actor
├── actor/               # 游戏逻辑实体，持有 ECS 世界状态
├── leaderboard/         # 排行榜服务：Redis + MySQL 榜单
├── common/              # 公共配置与工具类
├── pom.xml              # Maven 父 POM
└── README.md
```

---

## 🔧 工程化与待办事项

- [ ] **README 完善** – 本文档已按最佳实践重写，可直接合并。
- [ ] **自动化主题创建** – 使用 `KafkaAdmin` 配置 Bean，去除手动执行命令的步骤。
- [ ] **清理 `.gitignore`** – 已排除 `target/`、`logs/`、`*.iml` 等，避免构建产物污染仓库。
- [ ] **提交规范** – 建议采用 conventional commits (`feat`, `fix`, `chore`)，便于追踪变更。
- [ ] **序列化方案统一** – 可考虑仅保留 Protobuf 或 FlatBuffers 一种，降低项目复杂度。
- [ ] **添加单元测试** – 当前缺少测试，难以快速验证核心逻辑。
- [ ] **补充前端或模拟客户端** – 至少提供一个命令行模拟器，方便演示。

---

## 🤔 为什么这个项目值得一看？

- 如果你是 **分布式系统初学者**，可以在这里看到 Kafka、Redis 在游戏场景下的实战组合。
- 如果你好奇 **ECS 架构**，又不想啃 C++ 代码，这个 Java 实现是很好的阅读材料。
- 如果你想挑战 **“把简单问题复杂化”** 的乐趣，这个项目就是极佳的范例。

---

## 📜 许可证
暂无明确的许可证，当前仅供学习与交流使用。

---

> *snake_game 是一个初生牛犊的技术火花，欢迎 Fork、改进和拍砖。*
```

---

这份 README 会帮助任何访问这个仓库的人快速理解项目目的、运行步骤和后续改进方向。你完全可以直接使用，如果对某些细节需要调整可以告诉我。
