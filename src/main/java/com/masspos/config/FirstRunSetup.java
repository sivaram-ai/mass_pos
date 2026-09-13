package com.masspos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drops a commented {@code pos.properties} template into the data directory on first start, never
 * overwriting one, so the machine's own settings (terminal, printer, port) can be edited without
 * touching the installed app. Shop settings live in the database and are edited on screen.
 */
@Component
public class FirstRunSetup implements ApplicationRunner {

    static final String TEMPLATE = "pos-template.properties";
    private static final Logger log = LoggerFactory.getLogger(FirstRunSetup.class);

    private final PosProperties pos;

    public FirstRunSetup(PosProperties pos) {
        this.pos = pos;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path settings = pos.dataDir().resolve("pos.properties").toAbsolutePath();
        if (Files.notExists(settings)) {
            try (InputStream template = new ClassPathResource(TEMPLATE).getInputStream()) {
                Files.copy(template, settings);
            }
            log.info("Created {}: edit it to set this machine's terminal code and printer, then restart", settings);
        }
    }
}
