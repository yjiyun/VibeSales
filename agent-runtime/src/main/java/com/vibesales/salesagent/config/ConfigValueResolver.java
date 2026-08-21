package com.vibesales.salesagent.config;

/**
 * 统一读取运行配置。
 *
 * <p>当前项目既要兼容系统环境变量，也要兼容本地 `.env` 文件加载后的 System Property，
 * 因此这里集中处理“先读环境变量，再读 System Property”的优先级。
 */
public final class ConfigValueResolver {

    private ConfigValueResolver() {
    }

    public static String get(String... keys) {
        for (String key : keys) {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.isBlank()) {
                return envValue.trim();
            }
            String propertyValue = System.getProperty(key);
            if (propertyValue != null && !propertyValue.isBlank()) {
                return propertyValue.trim();
            }
        }
        return "";
    }

    public static String getOrDefault(String defaultValue, String... keys) {
        String value = get(keys);
        return value.isBlank() ? defaultValue : value;
    }

    public static boolean getBooleanOrDefault(boolean defaultValue, String... keys) {
        String value = get(keys);
        return value.isBlank() ? defaultValue : "true".equalsIgnoreCase(value);
    }

    public static int getIntOrDefault(int defaultValue, String... keys) {
        String value = get(keys);
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Invalid integer config for " + String.join("/", keys) + ": " + value,
                    exception);
        }
    }
}
