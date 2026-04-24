package snake.base;

public class Position {
  public int x, y;

  public Position() {}

  public Position(int x, int y) {
    this.x = x;
    this.y = y;
  }

  @Override
  public String toString() {
    return x + " " + y;
  }
}
