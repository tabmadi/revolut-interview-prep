package io.github.tabmadi.app;

import io.github.tabmadi.app.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application entry point. */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    /**
     * Starts the application.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        LOG.info("Starting {} at log level {}", config.name(), config.logLevel());
        LOG.info("Hello, World!");
    }
}
