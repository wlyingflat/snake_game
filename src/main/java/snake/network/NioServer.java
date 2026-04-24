package snake.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import snake.base.Config;
import snake.base.ILogger;
import snake.base.Logger;

public abstract class NioServer implements IServer {
  protected int port;
  protected ServerSocketChannel serverChannel;
  protected Selector selector;
  protected volatile boolean running = true;
  protected ILogger logger = Logger.getInstance();

  public NioServer(int port) {
    this.port = port;
  }

  @Override
  public void start() throws IOException {
    serverChannel = ServerSocketChannel.open();
    serverChannel.bind(new InetSocketAddress(port));
    serverChannel.configureBlocking(false);
    selector = Selector.open();
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    logger.info(getServerName() + " listening on port " + port);

    while (running) {
      selector.select(Config.SELECT_TIMEOUT);
      Iterator<SelectionKey> it = selector.selectedKeys().iterator();
      while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();
        if (!key.isValid()) continue;

        if (key.isAcceptable()) {
          acceptClient();
        } else if (key.isReadable()) {
          handleRead(key);
        } else if (key.isWritable()) {
          handleWrite(key);
        }
      }
    }
    cleanup();
  }

  @Override
  public void stop() {
    running = false;
    if (selector != null) selector.wakeup();
  }

  protected abstract String getServerName();

  protected abstract ISession createSession(SocketChannel channel);

  protected abstract void processMessage(ISession session, String jsonMessage);

  protected void onSessionClosed(ISession session) {}

  public void scheduleWrite(ISession session) {
    if (session instanceof NioSession) {
      SelectionKey key = ((NioSession) session).channel.keyFor(selector);
      if (key != null && key.isValid()) {
        int ops = key.interestOps();
        if ((ops & SelectionKey.OP_WRITE) == 0) {
          key.interestOps(ops | SelectionKey.OP_WRITE);
          selector.wakeup();
        }
      }
    }
  }

  protected void closeSession(ISession session) {
    if (session instanceof NioSession) {
      try {
        ((NioSession) session).channel.close();
      } catch (IOException e) {
        logger.warn("Error closing channel: " + e.getMessage());
      }
    }
    onSessionClosed(session);
  }

  private void acceptClient() throws IOException {
    SocketChannel client = serverChannel.accept();
    client.configureBlocking(false);
    ISession session = createSession(client);
    client.register(selector, SelectionKey.OP_READ, session);
    logger.debug("New client connected: " + client.getRemoteAddress());
  }

  private void handleRead(SelectionKey key) {
    ISession session = (ISession) key.attachment();
    if (!(session instanceof NioSession)) return;
    NioSession nioSession = (NioSession) session;
    SocketChannel client = nioSession.channel;
    ByteBuffer buf = nioSession.readBuffer;
    try {
      int bytesRead = client.read(buf);
      if (bytesRead == -1) {
        closeSession(session);
        return;
      }
      buf.flip();
      List<String> messages = nioSession.parseReadData(buf);
      buf.clear();

      for (String msg : messages) {
        processMessage(session, msg);
      }
    } catch (IOException e) {
      logger.warn("IOException in handleRead: " + e.getMessage());
      closeSession(session);
    } catch (Exception e) {
      logger.error("Error parsing message: " + e.getMessage());
      closeSession(session);
    }
  }

  /**
   * 修复后的 handleWrite： - 优先处理未完成的 pendingBuffer - 从队列中取出消息后立即移除，并构造独立 ByteBuffer 发送 -
   * 若发送未完成，将剩余数据保存为 pendingBuffer，避免重复发送
   */
  private void handleWrite(SelectionKey key) throws IOException {
    ISession session = (ISession) key.attachment();
    if (!(session instanceof NioSession)) return;
    NioSession nioSession = (NioSession) session;
    SocketChannel client = nioSession.channel;
    Queue<String> queue = nioSession.writeQueue;

    // 1. 处理未完成的 pendingBuffer
    ByteBuffer pending = nioSession.getPendingBuffer();
    if (pending != null && pending.hasRemaining()) {
      client.write(pending);
      if (pending.hasRemaining()) {
        return; // 仍未写完，等待下次写事件
      } else {
        nioSession.clearPendingBuffer(); // 发送完成
        // 继续处理队列中的下一条消息
      }
    }

    // 2. 处理队列中的消息
    while (!queue.isEmpty()) {
      String msg = queue.poll(); // 立即取出，防止重复处理
      byte[] body = msg.getBytes(StandardCharsets.UTF_8);
      int totalLen = 4 + body.length;

      // 为每条消息创建独立的 ByteBuffer（避免复用带来的状态问题）
      ByteBuffer bufferToSend = ByteBuffer.allocateDirect(totalLen);
      bufferToSend.putInt(body.length);
      bufferToSend.put(body);
      bufferToSend.flip();

      client.write(bufferToSend);
      if (bufferToSend.hasRemaining()) {
        // 未写完，保存剩余数据为 pendingBuffer，停止处理后续消息
        nioSession.setPendingBuffer(bufferToSend);
        break;
      }
      // 写完则继续处理下一条消息
    }

    // 3. 如果队列为空且没有未完成的数据，取消 OP_WRITE 关注
    if (queue.isEmpty() && nioSession.getPendingBuffer() == null) {
      key.interestOps(SelectionKey.OP_READ);
    }
  }

  protected void cleanup() {
    running = false;
    try {
      if (selector != null) selector.close();
      if (serverChannel != null) serverChannel.close();
    } catch (IOException e) {
      logger.error("Error during cleanup: " + e.getMessage());
    }
  }
}
