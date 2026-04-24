package snake.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import snake.util.Logger;

public abstract class NioServer {
  protected int port;
  protected ServerSocketChannel serverChannel;
  protected Selector selector;
  protected volatile boolean running = true;

  public NioServer(int port) {
    this.port = port;
  }

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

  public void stop() {
    running = false;
    if (selector != null) selector.wakeup();
  }

  protected abstract String getServerName();

  protected abstract NioSession createSession(SocketChannel channel);

  protected abstract void processMessage(NioSession session, String jsonMessage);

  protected void onSessionClosed(NioSession session) {}

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
      List<String> messages = session.parseReadData(buf);
      buf.clear();

      for (String msg : messages) {
        processMessage(session, msg);
      }
    } catch (IOException e) {
      Logger.warn("IOException in handleRead: " + e.getMessage());
      closeSession(session);
    } catch (Exception e) {
      Logger.error("Error parsing message: " + e.getMessage());
      closeSession(session);
    }
  }

  private void handleWrite(SelectionKey key) throws IOException {
    NioSession session = (NioSession) key.attachment();
    SocketChannel client = session.channel;
    Queue<String> queue = session.writeQueue;
    ByteBuffer buf = session.writeBuffer;

    while (!queue.isEmpty()) {
      String msg = queue.peek();
      byte[] body = msg.getBytes(StandardCharsets.UTF_8);
      int totalLen = 4 + body.length;

      if (totalLen > Config.BUFFER_SIZE) {
        // 超大消息单独分配
        ByteBuffer largeBuf = ByteBuffer.allocateDirect(totalLen);
        largeBuf.putInt(body.length);
        largeBuf.put(body);
        largeBuf.flip();
        client.write(largeBuf);
        if (largeBuf.hasRemaining()) {
          break;
        }
        queue.poll();
      } else {
        buf.clear();
        buf.putInt(body.length);
        buf.put(body);
        buf.flip();
        client.write(buf);
        if (buf.hasRemaining()) {
          break;
        }
        queue.poll();
      }
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
