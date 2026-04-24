package snake.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MainServerClient implements AutoCloseable {
  private final Socket socket;
  private final OutputStream out;
  private final InputStream in;
  private final ObjectMapper mapper = new ObjectMapper();

  public MainServerClient(String host, int port) throws IOException {
    socket = new Socket(host, port);
    out = socket.getOutputStream();
    in = socket.getInputStream();
  }

  public String register(String username, String password) throws IOException {
    Map<String, Object> req = new HashMap<>();
    req.put("cmd", "REGISTER");
    req.put("username", username);
    req.put("password", password);
    sendJson(req);
    return readResponse();
  }

  public String login(String username, String password) throws IOException {
    Map<String, Object> req = new HashMap<>();
    req.put("cmd", "LOGIN");
    req.put("username", username);
    req.put("password", password);
    sendJson(req);
    return readResponse();
  }

  public void logout(String username) throws IOException {
    Map<String, Object> req = new HashMap<>();
    req.put("cmd", "LOGOUT");
    req.put("username", username);
    sendJson(req);
    readResponse(); // discard
  }

  private void sendJson(Map<String, Object> json) throws IOException {
    String jsonStr = mapper.writeValueAsString(json);
    byte[] body = jsonStr.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocate(4 + body.length);
    buf.putInt(body.length);
    buf.put(body);
    out.write(buf.array());
    out.flush();
  }

  private String readResponse() throws IOException {
    byte[] lenBuf = new byte[4];
    int read = 0;
    while (read < 4) {
      int r = in.read(lenBuf, read, 4 - read);
      if (r == -1) throw new IOException("Connection closed");
      read += r;
    }
    ByteBuffer lengthBuffer = ByteBuffer.wrap(lenBuf);
    int length = lengthBuffer.getInt();
    if (length <= 0 || length > 1024 * 1024) {
      throw new IOException("Invalid message length");
    }
    byte[] body = new byte[length];
    read = 0;
    while (read < length) {
      int r = in.read(body, read, length - read);
      if (r == -1) throw new IOException("Connection closed");
      read += r;
    }
    String json = new String(body, StandardCharsets.UTF_8);
    try {
      ObjectNode root = (ObjectNode) mapper.readTree(json);
      String cmd = root.get("cmd").asText();
      if ("LOGIN_OK".equals(cmd)) {
        String gatewayHost = root.get("gatewayHost").asText();
        int gatewayPort = root.get("gatewayPort").asInt();
        return "OK\nGATEWAY " + gatewayHost + " " + gatewayPort;
      } else if ("REGISTER_OK".equals(cmd) || "LOGOUT_OK".equals(cmd)) {
        return "OK";
      } else if ("ERROR".equals(cmd)) {
        return "ERROR " + root.get("message").asText();
      } else {
        return json;
      }
    } catch (Exception e) {
      return json;
    }
  }

  @Override
  public void close() throws IOException {
    in.close();
    out.close();
    socket.close();
  }
}
