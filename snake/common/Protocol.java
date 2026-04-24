package snake.common;

public class Protocol {
  // 客户端 -> 主服务器/房间服务器
  public static final String CMD_REGISTER = "REG";
  public static final String CMD_LOGIN = "LOGIN";
  public static final String CMD_CREATE = "CREATE";
  public static final String CMD_JOIN = "JOIN";
  public static final String CMD_LOGOUT = "LOGOUT";
  public static final String CMD_ROOM_LIST = "ROOM_LIST";

  // 房间服务器内
  public static final String PLAYER = "PLAYER";
  public static final String QUIT = "QUIT";

  // 网关内
  public static final String USER = "USER";
  public static final String PING = "PING";
  public static final String PONG = "PONG";
  public static final String ROOM_LIST_UPDATE = "ROOM_LIST_UPDATE";

  // 主服务器 -> 客户端
  public static final String RESP_OK = "OK";
  public static final String RESP_ERROR = "ERROR";
  public static final String RESP_REDIRECT = "REDIRECT";

  // 房间服务器 -> 客户端
  public static final String STATE = "STATE";
  public static final String WELCOME = "WELCOME";
  public static final String YOU_DIED = "YOU DIED";
}
