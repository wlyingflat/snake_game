package snake.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Queue;
import snake.util.Logger;

/**
 * NIO 服务器抽象基类，统一处理： - Selector 事件循环 - accept / read / write 半包处理 - 会话创建与销毁的钩子方法 子类只需实现
 * createSession 和 processMessage，并可选覆盖 onSessionClosed。
 */
public abstract class NioServer {
  protected int port;
  protected ServerSocketChannel serverChannel;
  protected Selector selector;
  protected volatile boolean running = true;

  public NioServer(int port) {
    this.port = port;
  }

  /** 启动服务器，进入事件循环。 */
  public void start() throws IOException {
    serverChannel = ServerSocketChannel.open();
    serverChannel.bind(new InetSocketAddress(port));
    serverChannel.configureBlocking(false);
    selector = Selector.open();
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    Logger.info(getServerName() + " listening on port " + port);

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

  /** 停止服务器。 */
  public void stop() {
    running = false;
    if (selector != null) selector.wakeup();
  }

  // ---------- 抽象方法，由子类实现 ----------
  protected abstract String getServerName();

  protected abstract NioSession createSession(SocketChannel channel);

  protected abstract void processMessage(NioSession session, String message);

  // ---------- 可选钩子 ----------
  protected void onSessionClosed(NioSession session) {}

  // ---------- 公共方法 ----------
  /** 由 NioSession.enqueueResponse 调用，为指定会话注册 OP_WRITE 事件。 */
  public void scheduleWrite(NioSession session) {
    SelectionKey key = session.channel.keyFor(selector);
    if (key != null && key.isValid()) {
      int ops = key.interestOps();
      if ((ops & SelectionKey.OP_WRITE) == 0) {
        key.interestOps(ops | SelectionKey.OP_WRITE);
        selector.wakeup();
      }
    }
  }

  protected void closeSession(NioSession session) {
    try {
      session.channel.close();
    } catch (IOException e) {
      Logger.warn("Error closing channel: " + e.getMessage());
    }
    onSessionClosed(session);
  }

  // ---------- 私有 NIO 方法 ----------
  private void acceptClient() throws IOException {
    SocketChannel client = serverChannel.accept();
    client.configureBlocking(false);
    NioSession session = createSession(client);
    client.register(selector, SelectionKey.OP_READ, session);
    Logger.debug("New client connected: " + client.getRemoteAddress());
  }

  private void handleRead(SelectionKey key) {
    NioSession session = (NioSession) key.attachment();
    SocketChannel client = session.channel;
    ByteBuffer buf = session.readBuffer;
    try {
      int bytesRead = client.read(buf);
      if (bytesRead == -1) {
        closeSession(session);
        return;
      }
      buf.flip();
      byte[] data = new byte[buf.remaining()];
      buf.get(data);
      buf.clear();
      String chunk = new String(data, StandardCharsets.UTF_8);
      session.pendingMessage.append(chunk);

      String fullMsg;
      int idx;
      while ((idx = session.pendingMessage.indexOf("\n")) != -1) {
        fullMsg = session.pendingMessage.substring(0, idx).trim();
        session.pendingMessage.delete(0, idx + 1);
        if (!fullMsg.isEmpty()) {
          processMessage(session, fullMsg);
        }
      }
    } catch (IOException e) {
      Logger.warn("IOException in handleRead for " + client + ": " + e.getMessage());
      closeSession(session);
    }
  }

  private void handleWrite(SelectionKey key) throws IOException {
    NioSession session = (NioSession) key.attachment();
    SocketChannel client = session.channel;
    Queue<String> queue = session.writeQueue;
    ByteBuffer buf = ByteBuffer.allocate(Config.BUFFER_SIZE);
    while (!queue.isEmpty()) {
      String msg = queue.peek();
      byte[] bytes = (msg + "\n").getBytes(StandardCharsets.UTF_8);
      if (bytes.length > Config.BUFFER_SIZE) {
        ByteBuffer largeBuf = ByteBuffer.wrap(bytes);
        client.write(largeBuf);
        if (largeBuf.hasRemaining()) break;
      } else {
        buf.clear();
        buf.put(bytes);
        buf.flip();
        client.write(buf);
        if (buf.hasRemaining()) break;
      }
      queue.poll();
    }
    if (queue.isEmpty()) {
      key.interestOps(SelectionKey.OP_READ);
    }
  }

  protected void cleanup() {
    running = false;
    try {
      if (selector != null) selector.close();
      if (serverChannel != null) serverChannel.close();
    } catch (IOException e) {
      Logger.error("Error during cleanup: " + e.getMessage());
    }
  }
}
