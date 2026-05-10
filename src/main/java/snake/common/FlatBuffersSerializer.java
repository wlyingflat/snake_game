package snake.common;

import com.google.flatbuffers.FlatBufferBuilder;
import java.util.*;
import snake.domain.game.AgarGameState.AgarPlayerState;
import snake.fbs.*;

public class FlatBuffersSerializer {
  private final ThreadLocal<FlatBufferBuilder> builderTL =
      ThreadLocal.withInitial(() -> new FlatBufferBuilder(4096));

  public byte[] serializeAgarFrame(List<AgarPlayerState> players, List<Position> foods) {
    FlatBufferBuilder fbb = builderTL.get();
    fbb.clear();

    int[] ballOffsets = new int[players.size()];
    for (int i = 0; i < players.size(); i++) {
      AgarPlayerState ps = players.get(i);
      int nameOff = fbb.createString(ps.username);
      BallState.startBallState(fbb);
      BallState.addUsername(fbb, nameOff);
      BallState.addX(fbb, ps.x);
      BallState.addY(fbb, ps.y);
      BallState.addMass(fbb, ps.mass);
      ballOffsets[i] = BallState.endBallState(fbb);
    }
    int ballsVector = AgarFrame.createBallsVector(fbb, ballOffsets);

    int[] foodOffsets = new int[foods.size()];
    for (int i = 0; i < foods.size(); i++) {
      Position f = foods.get(i);
      foodOffsets[i] = snake.fbs.Vec2.createVec2(fbb, f.x, f.y);
    }
    int foodsVector = AgarFrame.createFoodVector(fbb, foodOffsets);

    AgarFrame.startAgarFrame(fbb);
    AgarFrame.addBalls(fbb, ballsVector);
    AgarFrame.addFood(fbb, foodsVector);
    int root = AgarFrame.endAgarFrame(fbb);
    fbb.finish(root);
    return fbb.sizedByteArray();
  }
}
