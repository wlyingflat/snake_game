package snake.infrastructure.event;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.*;
import snake.common.Config;
import snake.common.ILogger;
import snake.common.Logger;

public class KafkaEventProducer implements AutoCloseable {
  private final Producer<String, String> producer;
  private final ILogger logger = Logger.getInstance();
  private final boolean enabled;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public KafkaEventProducer() {
    this.enabled = Config.KAFKA_ENABLED;
    if (!enabled) {
      producer = null;
      logger.info("Kafka event producer disabled");
      return;
    }
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Config.KAFKA_BOOTSTRAP_SERVERS);
    props.put(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer");
    props.put(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer");
    props.put(ProducerConfig.RETRIES_CONFIG, 3);
    props.put(ProducerConfig.ACKS_CONFIG, "1");
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
    props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    this.producer = new KafkaProducer<>(props);
    logger.info("Kafka event producer initialized, servers=" + Config.KAFKA_BOOTSTRAP_SERVERS);
  }

  public void send(String topic, GameEvent event) {
    if (!enabled || producer == null || closed.get()) return;
    String key = null;
    if (event instanceof PlayerDiedEvent) key = ((PlayerDiedEvent) event).username;
    else if (event instanceof ScoreChangedEvent) key = ((ScoreChangedEvent) event).username;
    // 异步发送
    producer.send(
        new ProducerRecord<>(topic, key, event.toJson()),
        (metadata, exception) -> {
          if (exception != null) {
            logger.error("Failed to send event to Kafka: " + exception.getMessage());
          } else if (Config.DEBUG_MESSAGE_LOGGING) {
            logger.debug("Event sent: " + event.getEventType());
          }
        });
  }

  public void flush() {
    if (producer != null && !closed.get()) {
      producer.flush();
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      if (producer != null) {
        producer.flush();
        producer.close();
        logger.info("Kafka producer closed");
      }
    }
  }
}
