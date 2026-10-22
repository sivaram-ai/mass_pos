package com.masspos;

import com.masspos.config.DataDirMigration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PosApplication {

    public static void main(String[] args) {
        // Before anything resolves pos.data-dir: a till upgraded from the two-folder layout still
        // has its database in the old place, and logging would otherwise create the new folder
        // first and leave the shop looking at an empty till.
        DataDirMigration.migrateIfNeeded();
        SpringApplication.run(PosApplication.class, args);
    }
}
