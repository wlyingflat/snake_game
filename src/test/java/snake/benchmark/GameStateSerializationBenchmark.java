package snake.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
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
public class GameStateSerializationBenchmark {

  private FlatBuffersSerializer fbsSerializer;
  private ObjectMapper jsonMapper;
  private GameStateData sample;

  @Setup
  public void setup() {
    fbsSerializer = new FlatBuffersSerializer();
    jsonMapper = new ObjectMapper();

    // 构造满员房间(8个玩家)的快照
    GameState state = new GameState(1);
    for (int i = 0; i < Config.MAX_PLAYERS_PER_ROOM; i++) {
      state.addPlayer("p" + i);
    }
    state.update();
    sample = state.snapshot(null);
  }

  @Benchmark
  public byte[] flatbuffers() {
    return fbsSerializer.serializeGameState(sample);
  }

  @Benchmark
  public byte[] json() {
    try {
      return jsonMapper.writeValueAsBytes(sample);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
