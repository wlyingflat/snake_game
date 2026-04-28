package snake.distributed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import snake.infrastructure.persistence.RedisKeys;

class DistributedCoordinatorTest {
  private RedissonClient redisson;
  private DistributedCoordinator coordinator;

  @BeforeEach
  void setUp() {
    redisson = mock(RedissonClient.class);
    // 使用 doReturn 避免泛型问题
    RMap<String, Object> roomMap = mock(RMap.class);
    doReturn(roomMap).when(redisson).getMap(RedisKeys.ROOM_PREFIX + "1");
    when(roomMap.fastPutIfAbsent("nodeId", "worker-1")).thenReturn(true);

    RSet<Object> workerSet = mock(RSet.class);
    doReturn(workerSet).when(redisson).getSet(RedisKeys.WORKER_NODES);
    when(workerSet.readAll()).thenReturn(Set.of("worker-1"));

    coordinator = new DistributedCoordinator(redisson, "worker-1");
  }

  @Test
  void tryCreateRoomShouldSucceed() {
    boolean created = coordinator.tryCreateRoom(1, 8);
    assertTrue(created);
  }

  @Test
  void getActiveWorkersShouldReturnRegistered() {
    Set<String> workers = coordinator.getActiveWorkers();
    assertEquals(1, workers.size());
  }
}
