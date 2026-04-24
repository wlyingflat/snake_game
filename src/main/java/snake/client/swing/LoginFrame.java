package snake.client.swing;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import javax.swing.*;
import snake.client.MainServerClient;

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
    new Thread(
            () -> {
              try (MainServerClient client =
                  new MainServerClient(app.getServerHost(), app.getServerPort())) {
                String response = client.login(user, pass);
                if (response.startsWith("OK")) {
                  String[] lines = response.split("\n");
                  if (lines.length >= 2 && lines[1].startsWith("GATEWAY")) {
                    String[] parts = lines[1].split(" ");
                    if (parts.length == 3) {
                      String gatewayHost = parts[1];
                      int gatewayPort = Integer.parseInt(parts[2]);
                      SwingUtilities.invokeLater(
                          () -> {
                            dispose();
                            app.onLoginSuccess(user, gatewayHost, gatewayPort);
                          });
                      return;
                    }
                  }
                }
                SwingUtilities.invokeLater(
                    () ->
                        JOptionPane.showMessageDialog(
                            LoginFrame.this, "Login failed: " + response));
              } catch (IOException ex) {
                SwingUtilities.invokeLater(
                    () ->
                        JOptionPane.showMessageDialog(
                            LoginFrame.this, "Server connection error: " + ex.getMessage()));
              }
            })
        .start();
  }

  private void doRegister(ActionEvent e) {
    String user = usernameField.getText().trim();
    String pass = new String(passwordField.getPassword()).trim();
    if (user.isEmpty() || pass.isEmpty()) {
      JOptionPane.showMessageDialog(this, "Username and password required");
      return;
    }
    new Thread(
            () -> {
              try (MainServerClient client =
                  new MainServerClient(app.getServerHost(), app.getServerPort())) {
                String response = client.register(user, pass);
                if (response.startsWith("OK")) {
                  // 注册成功后自动登录
                  doLogin(e);
                } else {
                  SwingUtilities.invokeLater(
                      () ->
                          JOptionPane.showMessageDialog(
                              LoginFrame.this, "Register failed: " + response));
                }
              } catch (IOException ex) {
                SwingUtilities.invokeLater(
                    () ->
                        JOptionPane.showMessageDialog(
                            LoginFrame.this, "Server connection error: " + ex.getMessage()));
              }
            })
        .start();
  }
}
