package database;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

public class Database {

    public static String LOGIN_SCHEMA = "shitakis";

    private static HikariDataSource pConnection;

    public static void Init(String sHost, String sPort, String sSchema, String sUsername, String sPassword) {
        HikariConfig pConfig = new HikariConfig();

        pConfig.setJdbcUrl("jdbc:mariadb://" + sHost + ":" + sPort + "/" + sSchema);
        pConfig.setUsername(sUsername);
        pConfig.setPassword(sPassword);

        pConfig.setMaximumPoolSize(20);
        pConfig.setAutoCommit(true);
        pConfig.setLeakDetectionThreshold(60000);
        pConfig.setConnectionTestQuery("SELECT 1");
        pConfig.setIdleTimeout(30000);
        pConfig.setMaxLifetime(1800000);

        pConfig.addDataSourceProperty("cachePrepStmts", "true");
        pConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        pConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        pConfig.addDataSourceProperty("autoReconnect", "true");
        pConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
        pConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        pConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        pConfig.addDataSourceProperty("maintainTimeStats", "false");
        pConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        pConfig.addDataSourceProperty("useLocalSessionState", "true");
        pConfig.addDataSourceProperty("useServerPrepStmts", "false");
        pConfig.addDataSourceProperty("tcpNoDelay", "true");
        pConfig.addDataSourceProperty("tcpKeepAlive", "true");

        pConnection = new HikariDataSource(pConfig);
    }

    public static Connection GetConnection() {
        if (pConnection == null) {
            return null;
        }
        try {
            return pConnection.getConnection();
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static int Bind(PreparedStatement propSet, Object... command) {
        for (int i = 1; i <= command.length; i++) {
            Object cmd = command[i - 1];
            if (cmd != null) {
                try {
                    if (cmd instanceof Number) {
                        // Specific to only setByte calls, default Integer
                        if (cmd instanceof Byte) {
                            propSet.setByte(i, (Byte) cmd);
                        } else if (cmd instanceof Short) {
                            propSet.setShort(i, (Short) cmd);
                            // Specific to only setLong calls, default Integer
                        } else if (cmd instanceof Long) {
                            propSet.setLong(i, (Long) cmd);
                        } else if (cmd instanceof Double) {
                            propSet.setDouble(i, (Double) cmd);
                            // Almost all types are INT(11), so default to this
                        } else {
                            propSet.setInt(i, (Integer) cmd);
                        }
                        // If it is otherwise a String, we only require setString
                    } else if (cmd instanceof String) {
                        propSet.setString(i, (String) cmd);
                    } else if (cmd instanceof Boolean) {
                        propSet.setBoolean(i, (Boolean) cmd);
                    } else if (cmd instanceof Timestamp) {
                        propSet.setTimestamp(i, (Timestamp) cmd);
                    } else if (cmd instanceof Serializable) {
                        propSet.setObject(i, cmd);
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace(System.err);
                }
            } else {
                return DBResult.INVALID_CMD_ARG;
            }
        }
        return DBResult.SUCCESS_BIND;
    }

    public static int Execute(Connection con, PreparedStatement propSet, Object... command) throws SQLException {
        if (propSet != null) {
            int result = Bind(propSet, command);

            if (result > 0) {
                int rowsAffected = propSet.executeUpdate();
                if (rowsAffected == 0) {
                    String query = propSet.toString();
                    // The only valid DML statement for re-insertion is UPDATE.
                    if (!query.contains("DELETE FROM") && !query.contains("INSERT INTO")) {
                        // Substring based on if the query contains '?' IN params or not
                        if (query.contains("', parameters"))
                            query = query.substring(query.indexOf("UPDATE"), query.indexOf("', parameters"));
                        else
                            query = query.substring(query.indexOf("UPDATE"));

                        // Begin the new query, starting by converting an update to an insert
                        String newQuery = query.replaceAll("UPDATE", "INSERT INTO");

                        // Substring the FRONT rows (prior to WHERE condition)
                        String rows;
                        if (newQuery.contains("WHERE"))
                            rows = newQuery.substring(newQuery.indexOf("SET ") + "SET ".length(), newQuery.indexOf("WHERE "));
                        else
                            rows = newQuery.substring(newQuery.indexOf("SET ") + "SET ".length());
                        // Construct an array of every front row
                        String[] frontRows = rows.replaceAll(" = \\?, ", ", ").replaceAll(" = \\? ", ", ").split(", ");
                        // Not all queries perform an UPDATE with a WHERE condition, allocate empty back rows
                        String[] backRows = {};
                        // If the query does contain a WHERE condition, parse the back rows (everything after WHERE)
                        if (newQuery.contains("WHERE")) {
                            rows = newQuery.substring(newQuery.indexOf("WHERE ") + "WHERE ".length());
                            backRows = rows.replaceAll(" = \\? AND ", ", ").replaceAll(" = \\?", ", ").split(", ");
                        }
                        // Merge the front and back rows into one table, these are all columns being inserted
                        String[] allRows = new String[frontRows.length + backRows.length];
                        System.arraycopy(frontRows, 0, allRows, 0, frontRows.length);
                        System.arraycopy(backRows, 0, allRows, frontRows.length, backRows.length);

                        // Begin transforming the query - clear the rest of the string, transform to (Col1, Col2, Col3)
                        newQuery = newQuery.substring(0, newQuery.indexOf("SET "));
                        newQuery += "(";
                        for (String key : allRows) {
                            newQuery += key + ", ";
                        }
                        // Trim the remaining , added at the end of the last column
                        newQuery = newQuery.substring(0, newQuery.length() - ", ".length());

                        // Begin appending the VALUES(?, ?) for the total size there is rows
                        newQuery += ") VALUES(";
                        for (String notUsed : allRows) {
                            newQuery += "?, ";
                        }
                        // Trim the remaining , added at the end of the last column
                        newQuery = newQuery.substring(0, newQuery.length() - ", ".length());
                        newQuery += ")";

                        return Execute(con, con.prepareStatement(newQuery), command);
                    }
                }
                return rowsAffected;
            }
            return result;
        }
        return DBResult.INVALID_COMMAND;
    }

    public static class DBResult {
        public static final int
                /* If all GET operations completed successfully */
                SUCCESS = 0,
                /* If all POST operations completed successfully */
                SUCCESS_BIND = 1,
                /* If all POST operations completed successfully, but no rows were affected */
                SUCCESS_NOCHANGE = 2,
                /* If all POST operations completed successfully, and are batch statements */
                SUCCESS_BATCH = 3,
                /* If the SQL Query/Command is EMPTY or NULL */
                INVALID_COMMAND = -1,
                /* If the UnifiedDB is NULL or an exception occurred while pooling the DB */
                INVALID_SESSION = -2,
                /* If the PreparedStatement is NULL or an exception occurred while preparing it */
                INVALID_PROPSET = -3,
                /* If a command argument is NULL or an exception occurred while setting it */
                INVALID_CMD_ARG = -4,
                /* If the required parameter is a returned reference and it is NULL */
                INVALID_PARAMS = -5,
                /* If all other operations have succeeded, but an SQL exception has occurred */
                UNKNOWN_ERROR = -6;
    }
}