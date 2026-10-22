package ru.itmo.highload_ml.tracking.service;
import io.r2dbc.spi.R2dbcException;
final class SqlErrors {
    private SqlErrors() { }
    static boolean unique(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof R2dbcException sql && "23505".equals(sql.getSqlState())
                    && sql.getMessage().contains(constraint)) return true;
        }
        return false;
    }
}
