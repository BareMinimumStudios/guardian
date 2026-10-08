package com.bareminimumstudios.guardian.storage.jdbc;

import java.sql.DriverManager;

/** Child-process fixture: commits the apply intent, then exits without closing JDBC. */
public final class ItemJournalCrashWriter {
    public static void main(String[] args) throws Exception {
        Class.forName(args[0]);
        var connection = DriverManager.getConnection(args[1]);
        connection.setAutoCommit(false);
        try (var update = connection.prepareStatement("UPDATE ex_item_rollback SET phase = 'APPLYING' WHERE operation_uuid = ? AND phase = 'PREPARED'")) {
            update.setString(1, args[2]);
            if (update.executeUpdate() != 1) throw new IllegalStateException("No prepared operation");
        }
        try (var update = connection.createStatement()) {
            update.executeUpdate("UPDATE ex_meta SET meta_value = 'false' WHERE meta_key = 'clean_shutdown'");
        }
        connection.commit();
        System.out.println("APPLY_INTENT_COMMITTED");
        System.out.flush();
        Runtime.getRuntime().halt(23);
    }
}
