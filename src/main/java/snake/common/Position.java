package snake.common;

public class Position {
  public int x, y;

  public Position() {}

  public Position(int x, int y) {
    this.x = x;
    this.y = y;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Position position = (Position) o;
    return x == position.x && y == position.y;
  }

  @Override
  public int hashCode() {
    return 31 * x + y;
  }

  @Override
  public String toString() {
    return x + " " + y;
  }
}
