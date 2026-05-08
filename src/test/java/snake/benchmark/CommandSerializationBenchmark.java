package snake.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import snake.application.actor.EnhancedMessage;
import snake.common.JsonUtils;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class CommandSerializationBenchmark {

  private EnhancedMessage protoMsg;
  private String jsonText;
  private ObjectMapper mapper;

  @Setup
  public void setup() {
    mapper = JsonUtils.MAPPER;
    protoMsg =
        EnhancedMessage.newInstance()
            .init("INPUT", "testUser", 1, "gw-1", "{\"direction\":\"UP\"}");

    ObjectNode node = mapper.createObjectNode();
    node.put("cmd", "INPUT");
    node.put("username", "testUser");
    node.put("roomId", 1);
    node.put("gatewayId", "gw-1");
    node.put("rawMessage", "{\"direction\":\"UP\"}");
    jsonText = node.toString();
  }

  @TearDown
  public void teardown() {
    if (protoMsg != null) {
      protoMsg.recycle();
    }
  }

  @Benchmark
  public byte[] protobufRoundtrip() {
    byte[] data = protoMsg.toProtobuf();
    EnhancedMessage decoded = EnhancedMessage.fromProtobuf(data);
    if (decoded != null) {
      decoded.recycle();
    }
    return data;
  }

  @Benchmark
  public ObjectNode jsonRoundtrip() {
    try {
      ObjectNode node = (ObjectNode) mapper.readTree(jsonText);
      mapper.writeValueAsString(node);
      return node;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
