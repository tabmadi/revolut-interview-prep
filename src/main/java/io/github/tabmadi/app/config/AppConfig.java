package io.github.tabmadi.app.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Typed view over the application configuration.
 *
 * <p>Values are loaded from {@code application.conf} and may be overridden by environment variables
 * or JVM system properties, in that order of precedence.
 *
 * @param name the human readable application name
 * @param logLevel the root log level, e.g. {@code DEBUG} or {@code INFO}
 */
public record AppConfig(String name, String logLevel) {

    /**
     * Loads the configuration from the default sources.
     *
     * @return the parsed and validated configuration
     */
    public static AppConfig load() {
        return from(ConfigFactory.load());
    }

    /**
     * Maps an already parsed configuration tree onto this record.
     *
     * @param config the parsed configuration tree
     * @return the validated configuration
     */
    public static AppConfig from(Config config) {
        Config app = config.getConfig("app");
        return new AppConfig(app.getString("name"), app.getString("log-level"));
    }
}
