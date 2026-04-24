package snake.game.event;

import java.util.*;
import snake.base.GameStateData;
import snake.base.Position;

public class GameStateDiff {
  public int roomId;
  public long seq;
  public Position food; // null 表示无变化
  public Map<String, PlayerDiff> players = new HashMap<>();
  public List<String> died = new ArrayList<>();
  public List<GameStateData.PlayerInfo> newPlayers = new ArrayList<>(); // 新加入的全量
  public List<String> removedPlayers = new ArrayList<>();
}
