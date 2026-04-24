/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --topic game.player.score --partitions 3 --replication-factor 1
/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --topic game.player.died --partitions 3 --replication-factor 1
/usr/share/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --topic game.player.input --partitions 3 --replication-factor 1
WARNING: Due to limitations in metric names, topics with a period ('.') or underscore ('_') could collide. To avoid issues it is best to use either, but not both.
Error while executing topic command : Topic 'game.player.score' already exists.
[2026-04-24 21:18:37,199] ERROR org.apache.kafka.common.errors.TopicExistsException: Topic 'game.player.score' already exists.
 (org.apache.kafka.tools.TopicCommand)
WARNING: Due to limitations in metric names, topics with a period ('.') or underscore ('_') could collide. To avoid issues it is best to use either, but not both.
Error while executing topic command : Topic 'game.player.died' already exists.
[2026-04-24 21:18:38,433] ERROR org.apache.kafka.common.errors.TopicExistsException: Topic 'game.player.died' already exists.
 (org.apache.kafka.tools.TopicCommand)
WARNING: Due to limitations in metric names, topics with a period ('.') or underscore ('_') could collide. To avoid issues it is best to use either, but not both.
Created topic game.player.input.

