package snake.common;

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
import snake.util.ILogger;
import snake.util.Logger;

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

  private void handleWrite(SelectionKey key) throws IOException {
    ISession session = (ISession) key.attachment();
    if (!(session instanceof NioSession)) return;
    NioSession nioSession = (NioSession) session;
    SocketChannel client = nioSession.channel;
    Queue<String> queue = nioSession.writeQueue;
    ByteBuffer buf = nioSession.writeBuffer;

    while (!queue.isEmpty()) {
      String msg = queue.peek();
      byte[] body = msg.getBytes(StandardCharsets.UTF_8);
      int totalLen = 4 + body.length;

      if (totalLen > Config.BUFFER_SIZE) {
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
      logger.error("Error during cleanup: " + e.getMessage());
    }
  }
}
