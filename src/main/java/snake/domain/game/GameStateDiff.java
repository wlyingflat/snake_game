package snake.domain.game;

import java.util.*;
import snake.common.GameStateData;
import snake.common.Position;

public class GameStateDiff {
  public int roomId;
  public long seq;
  public Position food; // null 表示无变化
  public Map<String, PlayerDiff> players = new HashMap<>();
  public List<String> died = new ArrayList<>();
  public List<GameStateData.PlayerInfo> newPlayers = new ArrayList<>(); // 新加入的全量
  public List<String> removedPlayers = new ArrayList<>();
}
