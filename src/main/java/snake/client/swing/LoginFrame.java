// LoginFrame.java - 美化版
package snake.client.swing;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.*;
import snake.client.GatewayClient;

public class LoginFrame extends JFrame {
  private final GameApp app;
  private JTextField usernameField;
  private JPasswordField passwordField;
  private JButton loginBtn, registerBtn;
  private JPanel contentPanel;

  public LoginFrame(GameApp app) {
    this.app = app;
    initUI();
  }

  private void initUI() {
    setTitle("Snake Game - Login");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(450, 320);
    setLocationRelativeTo(null);
    setResizable(false);

    // 主背景面板 (渐变)
    contentPanel =
        new JPanel() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            GradientPaint gp =
                new GradientPaint(
                    0, 0, new Color(30, 30, 50), 0, getHeight(), new Color(10, 10, 25));
            g2d.setPaint(gp);
            g2d.fillRect(0, 0, getWidth(), getHeight());
          }
        };
    contentPanel.setLayout(new GridBagLayout());
    contentPanel.setOpaque(false);
    setContentPane(contentPanel);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(10, 15, 10, 15);
    gbc.fill = GridBagConstraints.HORIZONTAL;

    // 标题
    JLabel titleLabel = new JLabel("SNAKE GAME", SwingConstants.CENTER);
    titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 28));
    titleLabel.setForeground(new Color(180, 220, 100));
    titleLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 20, 0));
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.gridwidth = 2;
    contentPanel.add(titleLabel, gbc);

    // 用户名
    gbc.gridwidth = 1;
    gbc.gridy = 1;
    gbc.gridx = 0;
    JLabel userLabel = createStyledLabel("Username:");
    contentPanel.add(userLabel, gbc);

    usernameField = createStyledTextField();
    gbc.gridx = 1;
    contentPanel.add(usernameField, gbc);

    // 密码
    gbc.gridy = 2;
    gbc.gridx = 0;
    JLabel passLabel = createStyledLabel("Password:");
    contentPanel.add(passLabel, gbc);

    passwordField = createStyledPasswordField();
    gbc.gridx = 1;
    contentPanel.add(passwordField, gbc);

    // 按钮面板
    gbc.gridy = 3;
    gbc.gridx = 0;
    gbc.gridwidth = 2;
    gbc.insets = new Insets(25, 15, 15, 15);
    JPanel btnPanel = new JPanel(new GridLayout(1, 2, 20, 0));
    btnPanel.setOpaque(false);

    loginBtn = createStyledButton("Login", new Color(70, 130, 200));
    registerBtn = createStyledButton("Register", new Color(100, 180, 100));

    btnPanel.add(loginBtn);
    btnPanel.add(registerBtn);
    contentPanel.add(btnPanel, gbc);

    // 事件
    loginBtn.addActionListener(this::doLogin);
    registerBtn.addActionListener(this::doRegister);
  }

  private JLabel createStyledLabel(String text) {
    JLabel label = new JLabel(text);
    label.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    label.setForeground(Color.WHITE);
    return label;
  }

  private JTextField createStyledTextField() {
    JTextField field = new JTextField(15);
    field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    field.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 80, 120), 1),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)));
    field.setBackground(new Color(50, 50, 70));
    field.setForeground(Color.WHITE);
    field.setCaretColor(Color.WHITE);
    return field;
  }

  private JPasswordField createStyledPasswordField() {
    JPasswordField field = new JPasswordField(15);
    field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    field.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 80, 120), 1),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)));
    field.setBackground(new Color(50, 50, 70));
    field.setForeground(Color.WHITE);
    field.setCaretColor(Color.WHITE);
    return field;
  }

  private JButton createStyledButton(String text, Color bgColor) {
    JButton btn =
        new JButton(text) {
          @Override
          protected void paintComponent(Graphics g) {
            if (getModel().isRollover()) {
              setBackground(bgColor.brighter());
            } else {
              setBackground(bgColor);
            }
            super.paintComponent(g);
          }
        };
    btn.setFont(new Font("Segoe UI", Font.BOLD, 14));
    btn.setForeground(Color.WHITE);
    btn.setBackground(bgColor);
    btn.setFocusPainted(false);
    btn.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
    btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    btn.setOpaque(true);
    return btn;
  }

  // 业务逻辑与原版一致，仅修改回调中界面相关部分
  private void doLogin(ActionEvent e) {
    authenticate(
        usernameField.getText().trim(), new String(passwordField.getPassword()).trim(), "LOGIN");
  }

  private void doRegister(ActionEvent e) {
    authenticate(
        usernameField.getText().trim(), new String(passwordField.getPassword()).trim(), "REGISTER");
  }

  private void authenticate(String username, String password, String cmd) {
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
                  if (resp.contains("\"cmd\":\"LOGIN_OK\"")
                      || resp.contains("\"cmd\":\"REGISTER_OK\"")) {
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
