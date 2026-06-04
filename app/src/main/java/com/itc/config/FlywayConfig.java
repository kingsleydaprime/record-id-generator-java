package com.itc.config;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class FlywayConfig {
    public static void migrate() {
        Properties props = new Properties();
        try (InputStream in = FlywayConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(
                        props.getProperty("db.url"),
                        props.getProperty("db.user"),
                        props.getProperty("db.password")
                )
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();
    }
}
