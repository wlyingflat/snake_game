package snake.common;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class GameStateData {
  public int roomId;
  public Position food;
  public Position[] obstacles = new Position[Config.OBSTACLE_COUNT];
  public int obstacleCount;
  public PlayerInfo[] players = new PlayerInfo[Config.MAX_PLAYERS_PER_ROOM];
  public int playerCount;
  public int activePlayers;
  public int totalPlayers;

  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  public static class PlayerInfo {
    public String name;
    public Position head;
    public Position[] body = new Position[Config.MAX_SNAKE_LENGTH];
    public int length;
    public Direction direction;
    public int score;
    public boolean isDead;
    public boolean isYou;
  }
}
