package database;

/**
 *
 * @author Smoke
 */
public interface ObjectRelationalModel {
    String ToString();
    String GetIncrementKey();
    String GetTableName();
    String GetSchemaName();
    String GetInsertStatement();
    String GetUpdateStatement();
    String GetSelectStatement();
    String[] GetTableColumnNames();
    Class<?>[] GetTableColumnTypes();
}
