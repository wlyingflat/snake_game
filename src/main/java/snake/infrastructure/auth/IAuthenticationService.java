package snake.infrastructure.auth;

public interface IAuthenticationService {
  boolean register(String username, String password);

  boolean login(String username, String password);

  void logout(String username);
}
