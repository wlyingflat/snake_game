package snake.benchmark;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import snake.common.Config;
import snake.common.FlatBuffersSerializer;
import snake.common.GameStateData;
import snake.domain.game.GameState;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class FlatBuffersAccessBenchmark {

  private byte[] serializedData;
  private FlatBuffersSerializer serializer;
  private GameStateData sample;

  @Setup
  public void setup() {
    serializer = new FlatBuffersSerializer();
    GameState state = new GameState(1);
    for (int i = 0; i < Config.MAX_PLAYERS_PER_ROOM; i++) {
      state.addPlayer("p" + i);
    }
    state.update();
    sample = state.snapshot(null);
    serializedData = serializer.serializeGameState(sample);
  }

  @Benchmark
  public int readAllPlayerNames() {
    ByteBuffer bb = ByteBuffer.wrap(serializedData);
    snake.fbs.GameState gameState = snake.fbs.GameState.getRootAsGameState(bb);
    int sum = 0;
    int count = gameState.playersLength();
    for (int i = 0; i < count; i++) {
      snake.fbs.Player player = gameState.players(i);
      sum += player.name().length();
    }
    return sum; // 防止死代码消除
  }
}
