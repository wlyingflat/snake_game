package snake.fbs;

@SuppressWarnings("unused")
public final class Direction {
  private Direction() {}

  public static final byte UP = 0;
  public static final byte DOWN = 1;
  public static final byte LEFT = 2;
  public static final byte RIGHT = 3;
  public static final String[] names = {"UP", "DOWN", "LEFT", "RIGHT"};

  public static String name(int e) {
    return names[e];
  }
}
