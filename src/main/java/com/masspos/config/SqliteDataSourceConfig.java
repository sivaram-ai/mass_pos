package com.masspos.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;

@Configuration(proxyBeanMethods = false)
public class SqliteDataSourceConfig {

    /**
     * Boot's standard Hikari pool, declared here only so that {@code pos.data-dir} exists before the
     * first connection: SQLite creates the database file on connect, but not its parent directory.
     * Pool and pragma settings stay in application.properties.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties, PosProperties pos) throws IOException {
        Files.createDirectories(pos.dataDir());
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
