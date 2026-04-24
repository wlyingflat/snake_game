package snake.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import snake.util.Logger;

public class PropertiesConfigProvider implements IConfigProvider {
  private final Properties props = new Properties();

  public PropertiesConfigProvider(String filename) {
    try (InputStream input = new FileInputStream(filename)) {
      props.load(input);
      Logger.getInstance().info("Loaded config from " + filename); // 改为 getInstance()
    } catch (IOException e) {
      Logger.getInstance()
          .warn(filename + " not found, using built-in defaults"); // 改为 getInstance()
    }
  }

  @Override
  public int getInt(String key, int defaultValue) {
    String val = props.getProperty(key);
    if (val != null) {
      try {
        return Integer.parseInt(val);
      } catch (NumberFormatException e) {
        // ignore
      }
    }
    return defaultValue;
  }

  @Override
  public String getString(String key, String defaultValue) {
    return props.getProperty(key, defaultValue);
  }
}
