package snake.distributed;

import java.util.HashSet;
import java.util.Set;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import snake.common.ILogger;
import snake.common.Logger;
import snake.infrastructure.persistence.RedisKeys;

/** 负责 Worker 和 Gateway 节点的注册与注销。 */
public class NodeRepository {
  private final RedissonClient redisson;
  private final ILogger logger = Logger.getInstance();

  public NodeRepository(RedissonClient redisson) {
    this.redisson = redisson;
  }

  // ---------- Worker 节点 ----------
  public void registerWorker(String workerId) {
    RSet<String> workers = redisson.getSet(RedisKeys.WORKER_NODES);
    workers.add(workerId);
    logger.info("Worker registered: " + workerId);
  }

  public void unregisterWorker(String workerId) {
    RSet<String> workers = redisson.getSet(RedisKeys.WORKER_NODES);
    workers.remove(workerId);
    logger.info("Worker unregistered: " + workerId);
  }

  public Set<String> getActiveWorkers() {
    Set<String> workers = new HashSet<>();
    for (Object obj : redisson.getSet(RedisKeys.WORKER_NODES).readAll()) {
      workers.add(obj.toString());
    }
    return workers;
  }

  // ---------- Gateway 节点 ----------
  public void registerGateway(String gatewayId) {
    redisson.getSet(RedisKeys.GATEWAY_NODES).add(gatewayId);
    logger.info("Gateway registered: " + gatewayId);
  }

  public void unregisterGateway(String gatewayId) {
    redisson.getSet(RedisKeys.GATEWAY_NODES).remove(gatewayId);
    logger.info("Gateway unregistered: " + gatewayId);
  }
}
