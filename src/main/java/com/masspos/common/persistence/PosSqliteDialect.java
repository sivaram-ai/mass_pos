package com.masspos.common.persistence;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.dialect.Dialect;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.internal.StandardTableExporter;
import org.hibernate.tool.schema.spi.Exporter;

import java.util.List;

import static java.util.stream.Collectors.joining;

/**
 * Hibernate 6's {@link SQLiteDialect}, plus foreign keys. SQLite cannot
 * {@code ALTER TABLE ... ADD CONSTRAINT}, so the stock dialect reports {@code hasAlterTable() = false}
 * and Hibernate silently drops every foreign key, leaving {@code foreign_keys=true} nothing to
 * enforce. This dialect declares them inline in {@code CREATE TABLE}, which SQLite does support.
 *
 * <p>Only tables that Hibernate creates get the constraints; an existing table is never rebuilt.
 * That gap closes once the schema moves to versioned migrations.
 */
public class PosSqliteDialect extends SQLiteDialect {

    private final Exporter<Table> tableExporter = new InlineForeignKeyTableExporter(this);

    @Override
    public Exporter<Table> getTableExporter() {
        return tableExporter;
    }

    private static final class InlineForeignKeyTableExporter extends StandardTableExporter {

        InlineForeignKeyTableExporter(Dialect dialect) {
            super(dialect);
        }

        /** Last hook before the closing parenthesis of CREATE TABLE, i.e. where table constraints go. */
        @Override
        protected void applyTableCheck(Table table, StringBuilder buf) {
            super.applyTableCheck(table, buf);
            for (ForeignKey foreignKey : table.getForeignKeys().values()) {
                if (!foreignKey.isCreationEnabled()) {
                    continue;
                }
                Table referencedTable = foreignKey.getReferencedTable();
                List<Column> referencedColumns = foreignKey.isReferenceToPrimaryKey()
                        ? referencedTable.getPrimaryKey().getColumns()
                        : foreignKey.getReferencedColumns();
                buf.append(", ");
                if (foreignKey.getName() != null) {
                    buf.append("constraint ").append(foreignKey.getName()).append(' ');
                }
                buf.append("foreign key (").append(columnList(foreignKey.getColumns()))
                        .append(") references ").append(referencedTable.getQuotedName(dialect))
                        .append(" (").append(columnList(referencedColumns)).append(')');
            }
        }

        private String columnList(List<Column> columns) {
            return columns.stream().map(column -> column.getQuotedName(dialect)).collect(joining(", "));
        }
    }
}
