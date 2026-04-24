package snake.client.swing;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import snake.client.GatewayClient;

public class LoginFrame extends JFrame {
  private final GameApp app;
  private JTextField usernameField;
  private JPasswordField passwordField;
  private JButton loginBtn;
  private JButton registerBtn;

  public LoginFrame(GameApp app) {
    this.app = app;
    setTitle("Snake Game - Login");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(400, 250);
    setLocationRelativeTo(null);
    setLayout(new GridBagLayout());

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(5, 5, 5, 5);

    JLabel userLabel = new JLabel("Username:");
    JLabel passLabel = new JLabel("Password:");
    usernameField = new JTextField(15);
    passwordField = new JPasswordField(15);
    loginBtn = new JButton("Login");
    registerBtn = new JButton("Register");

    gbc.gridx = 0;
    gbc.gridy = 0;
    add(userLabel, gbc);
    gbc.gridx = 1;
    add(usernameField, gbc);
    gbc.gridx = 0;
    gbc.gridy = 1;
    add(passLabel, gbc);
    gbc.gridx = 1;
    add(passwordField, gbc);
    gbc.gridx = 0;
    gbc.gridy = 2;
    gbc.gridwidth = 2;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 10, 0));
    buttonPanel.add(loginBtn);
    buttonPanel.add(registerBtn);
    add(buttonPanel, gbc);

    loginBtn.addActionListener(this::doLogin);
    registerBtn.addActionListener(this::doRegister);
  }

  private void doLogin(ActionEvent e) {
    String user = usernameField.getText().trim();
    String pass = new String(passwordField.getPassword()).trim();
    if (user.isEmpty() || pass.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Username and password required");
      return;
    }
    authenticate(user, pass, "LOGIN");
  }

  private void doRegister(ActionEvent e) {
    String user = usernameField.getText().trim();
    String pass = new String(passwordField.getPassword()).trim();
    if (user.isEmpty() || pass.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Username and password required");
      return;
    }
    authenticate(user, pass, "REGISTER");
  }

  private void authenticate(String username, String password, String cmd) {
    // 禁用按钮防止重复点击
    loginBtn.setEnabled(false);
    registerBtn.setEnabled(false);

    new Thread(
            () -> {
              GatewayClient gateway = new GatewayClient(app.getServerHost(), app.getServerPort());
              if (!gateway.connect()) {
                SwingUtilities.invokeLater(
                    () -> {
                      JOptionPane.showMessageDialog(
                          LoginFrame.this, "Failed to connect to gateway");
                      loginBtn.setEnabled(true);
                      registerBtn.setEnabled(true);
                    });
                return;
              }

              Map<String, Object> msg = new HashMap<>();
              msg.put("cmd", cmd);
              msg.put("username", username);
              msg.put("password", password);
              gateway.sendJson(msg);
              gateway.startMessageReceiver();

              long start = System.currentTimeMillis();
              boolean success = false;
              String errorMsg = "Unknown error";

              while (System.currentTimeMillis() - start < 5000) {
                String resp = gateway.pollMessage();
                if (resp != null) {
                  if (resp.contains("\"cmd\":\"LOGIN_OK\"")) {
                    success = true;
                    break;
                  } else if (resp.contains("\"cmd\":\"REGISTER_OK\"")) {
                    // 注册成功，自动转为登录
                    success = true;
                    break;
                  } else if (resp.contains("\"cmd\":\"ERROR\"")) {
                    errorMsg = resp;
                    break;
                  }
                }
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
              }

              if (success) {
                SwingUtilities.invokeLater(
                    () -> {
                      app.setGatewayClient(gateway);
                      dispose();
                      app.onLoginSuccess(username);
                    });
              } else {
                final String finalError = errorMsg;
                SwingUtilities.invokeLater(
                    () -> {
                      JOptionPane.showMessageDialog(
                          LoginFrame.this, "Authentication failed: " + finalError);
                      gateway.close();
                      loginBtn.setEnabled(true);
                      registerBtn.setEnabled(true);
                    });
              }
            })
        .start();
  }
}
