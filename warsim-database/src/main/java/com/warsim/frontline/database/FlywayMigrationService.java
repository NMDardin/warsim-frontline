package com.warsim.frontline.database;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

public final class FlywayMigrationService {
    public void migrate(DataSource dataSource, String schema) {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("frontline_schema_history")
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
            .migrate();
    }
}
