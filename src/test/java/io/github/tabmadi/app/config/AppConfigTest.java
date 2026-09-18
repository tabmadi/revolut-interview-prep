package io.github.tabmadi.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    @DisplayName("loads the defaults shipped in application.conf")
    void loadsDefaults() {
        AppConfig config = AppConfig.load();

        assertThat(config.name()).isEqualTo("java-template");
        assertThat(config.logLevel()).isEqualTo("INFO");
    }

    @Test
    @DisplayName("maps an explicit configuration tree")
    void mapsExplicitTree() {
        AppConfig config =
                AppConfig.from(ConfigFactory.parseString("app { name = \"custom\", log-level = \"DEBUG\" }"));

        assertThat(config).isEqualTo(new AppConfig("custom", "DEBUG"));
    }

    @Test
    @DisplayName("fails fast when a required key is missing")
    void failsOnMissingKey() {
        assertThatThrownBy(() -> AppConfig.from(ConfigFactory.parseString("app { name = \"custom\" }")))
                .isInstanceOf(ConfigException.Missing.class);
    }
}
