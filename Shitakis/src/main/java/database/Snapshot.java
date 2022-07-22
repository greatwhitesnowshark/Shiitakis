package database;

import java.lang.reflect.Array;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Smoke
 *
 *
 * Smoke [vs.] Java's Object Relational Model
 * ---------------------------------------------
 *
 * Java ORM Problem:
 *      According to the internet, ORM is shit design and leads to bad programs..?
 *      Internet is stupid though because the implementations of this that are available are just... too bulky
 *      and handled poorly. This is a poor man or simple man's implementation of automating database handling
 *      using ORM as the design paradigm in tow.
 *
 * <Tested against existing Java-ORM practices and excels in comparison>
 *
 * This abstract class is intended to be implemented underneath any object whose values must be:
 *      1. Loaded from the database
 *      2. Saved to the database
 *      3. Checked periodically, flushed when needed, during this object's life-cycle
 *
 * How to use:
 *      1. Create a new class (ex: Character.java) and implement this class properly
 *      2. Make sure it loads properly from the DB by using the Snapshot::ToString() function
 *      3. Call Snapshot::Update() at the intersection where you would like to check for changes and flush
 *
 * Best practice:
 *      1. If you make sure this object is also called on socket disconnect / object reallocation then you will never
 *      worry about any issues involving saves failing from coder oversight or error. It also provides the benefit of
 *      not having to manually track and save features as you create new ones or for relocating calls for flushing data.
 *      2. Technically, if Update() is called on socket destruction / migration there is never a need to periodically
 *      perform aggregated updates at all; BUT in a production environment - we have the resources available and it
 *      would be a risk to not periodically flush our data and we're smarter than that (recommended: every 30m & migration)
 */
public abstract class Snapshot implements ObjectRelationalModel {

    protected boolean bLoaded;
    protected boolean bSave, bAutoFlush;
    protected Object pKeyValue;
    protected Map<String, Object> mSnapshot;
    protected Map<String, Object> mCachedUpdate;
    protected ReentrantLock pUpdateThenFlushLock = new ReentrantLock();

    /**
     * Creates a Snapshot wrapper around an object which has saveable properties; loads from DB by default on instantiation
     * @param pKeyValue The value of the auto-increment-key or table-key to retrieve the row for this object via unique ID
     */
    public Snapshot(Object pKeyValue) {
        this.mSnapshot = new LinkedHashMap<>(GetTableColumnNames().length);
        this.mCachedUpdate = new LinkedHashMap<>(0);
        this.pKeyValue = pKeyValue;
        this.bLoaded = LoadFromDB();
        this.bAutoFlush = false;
    }

    /**
     * Creates a Snapshot wrapper around an object which has saveable properties; loads from DB by default on instantiation
     * @param pKeyValue The value of the auto-increment-key or table-key to retrieve the row for this object via unique ID
     * @param bAutoLoad Manual option to ignore the default load-from-database mechanism; if set to false, the data must be loaded intentionally after the Snapshot was created
     */
    public Snapshot(Object pKeyValue, boolean bAutoLoad) {
        this.mSnapshot = new LinkedHashMap<>(GetTableColumnNames().length);
        this.mCachedUpdate = new LinkedHashMap<>(0);
        this.pKeyValue = pKeyValue;
        this.bLoaded = bAutoLoad && LoadFromDB();
        this.bAutoFlush = false;
    }

    /**
     * Creates a Snapshot wrapper around an object which has saveable properties; loads from DB by default on instantiation
     * @param pKeyValue The value of the auto-increment-key or table-key to retrieve the row for this object via unique ID
     * @param bAutoLoad Manual option to ignore the default load-from-database mechanism; if set to false, the data must be loaded intentionally after the Snapshot was created
     * @param bAutoFlush Manual option to enable flushing to the database every time an update is measured, instead of caching updates and flushing all at one time efficiently;
     *                   if set to false, you must call this object's Update()|Flush() methods independently to aggregate and flush changes to the DB
     */
    public Snapshot(Object pKeyValue, boolean bAutoLoad, boolean bAutoFlush) {
        this.mSnapshot = new LinkedHashMap<>(GetTableColumnNames().length);
        this.mCachedUpdate = new LinkedHashMap<>(0);
        this.pKeyValue = pKeyValue;
        if (bAutoLoad) {
            this.bLoaded = LoadFromDB();
        }
        this.bAutoFlush = bAutoFlush;
    }

    /**
     * In a nutshell, we use the database table names to search for the object-class field names of our saveable object
     *
     * We use reflection (thank you java for improving its efficiency past 8) to verify and aggregate new object values
     *
     * If any field values have changed from the object's initial "snapshot", or collection of original field values loaded,
     * we store the new field+value into our cached updates map, waiting for the right opportunity to bulk-flush only the
     * aggregated changes to the database nice and efficient-like
     *
     * This method at minimum will insert updated field values to be saved into our cached map, but in most situations
     * will also perform creating the simplified SQL query as well as saving to DB automatically with no oversight necessary
     */
    public void Update() {
        for (String sFieldName : GetTableColumnNames()) try {
            Object pLastValue = mCachedUpdate.get(sFieldName);
            if (pLastValue == null) {
                pLastValue = mSnapshot.get(sFieldName);
            }
            Object pNewValue = getClass().getDeclaredField(sFieldName).get(this);
            if (pNewValue == null) {
                pNewValue = pLastValue;
            }
            if (!Objects.deepEquals(pLastValue, pNewValue)) {
                mCachedUpdate.put(sFieldName, pNewValue);
                if (!bSave) {
                    bSave = true;
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        if (bSave || bAutoFlush) {
            SaveToDB();
        }
    }

    /**
     * Just a way to Update() then SaveToDB() all in one go, made a method for it only to add thread-safety in case this is poorly implemented; should be used if (bSave == FALSE)
     */
    public void FlushToDB() {
        pUpdateThenFlushLock.lock();
        try {
            Update();
            SaveToDB();
        } finally {
            pUpdateThenFlushLock.unlock();
        }
    }

    /**
     * Generates the aggregated statement for updated field values in the best way for SQL to interpret the data (quickest);
     * and posts the flush statement to the database. Afterwards, resets the objects update cache to default and awaits
     * further changes to become available for posting
     *
     * @return True, if anything was available in the cached field, saves the data, resets the cache;
     *         False, if there was nothing that needed to be saved (no changed saveable properties since the last flush)
     */
    protected boolean SaveToDB() {
        if (!mCachedUpdate.isEmpty()) {
            try (Connection con = Database.GetConnection()) { // will have to fill this method yourself
                if (con != null) {
                    try (PreparedStatement ps = con.prepareStatement(GetUpdateStatement(), Statement.RETURN_GENERATED_KEYS)) {
                        int i = 1;
                        for (Map.Entry<String, Object> o : mCachedUpdate.entrySet()) {
                            ps.setObject(i++, o.getValue());
                        }

                        Database.Execute(con, ps, mCachedUpdate.values()); // will have to fill this method yourself
                        mSnapshot.putAll(mCachedUpdate);

                        mCachedUpdate.clear();
                        if (!bLoaded) {
                            bLoaded = true;
                        }
                        return true;
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * This is mostly used for printing / debugging or verifying your saved objects field outputs
     *
     * @see Snapshot::ToString();
     */
    protected void LoadFromSelf() {
        mSnapshot.clear();
        mCachedUpdate.clear();
        for (int i = 0; i < GetTableColumnNames().length; i++) {
            try {
                Object pValue = getClass().getDeclaredField(GetTableColumnNames()[i]).get(this);
                mSnapshot.put(GetTableColumnNames()[i], pValue);
            } catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Default method for loading all of an objects declared saveable fields from the corresponding SQL table;
     * method is only ever invoked at the time of the object's creation
     *
     * @return True, if the SELECT query generated was validated and no errors were thrown when retrieving the data;
     *         False, if no data could be found or stored due to an invalid query or incorrect statement values used
     */
    protected boolean LoadFromDB() {
        if (!bLoaded) {
            String[] aColumnNames = GetTableColumnNames();
            Class<?>[] aColumnTypes = GetTableColumnTypes();
            String sSelectQuery = GetSelectStatement();
            if (!sSelectQuery.contains(" = ") && !sSelectQuery.contains("like")) {
                return false;
            }
            try (Connection con = Database.GetConnection()) { // will have to fill this method yourself
                if (con != null) {
                    try (PreparedStatement ps = con.prepareStatement(sSelectQuery, Statement.RETURN_GENERATED_KEYS)) {
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                for (int i = 0; i < aColumnNames.length; i++) {
                                    Object pValue = rs.getObject((i + 1), aColumnTypes[i]);
                                    try {
                                        getClass().getDeclaredField(aColumnNames[i]).set(this, pValue);
                                    } catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
                                        e.printStackTrace();
                                    }
                                    mSnapshot.put(aColumnNames[i], pValue);
                                }
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return !mSnapshot.isEmpty();
    }

    /**
     * An override function for adding additional SELECT query arguments if loading cannot be done with a single key
     * or if you need to use a value that you don't have loaded yet (because its on another object typically not yet loaded)
     *
     * @return Any additional string arguments to be used with the auto-increment-key argument to retrieve the DB row
     */
    public String GetAdditionalArguments() { //override for adding multiple location arguments in the select query
        return "";
    }

    /**
     * Utility function for combining column names into a readable string for the SQL statement
     *
     * @return A usable string-reference for the SQL row or column names on this Snapshot
     */
    private String GetColumnNames() {
        return String.join(", ", GetTableColumnNames());
    }

    /**
     * Utility function for combining the column values into a readable string for the SQL statement
     *
     * @return A usable string-reference for the SQL field values on this Snapshot
     */
    private String GetValues() {
        StringBuilder sValues = new StringBuilder();
        for (int i = 0; i < mSnapshot.keySet().size(); i++) {
            if (i != 0) {
                sValues.append(", ?");
            } else {
                sValues.append("?");
            }
        }
        return sValues.toString();
    }

    /**
     * Designates the name of the SQL table where this Snapshot's data resides
     *
     * @return A usable string-reference for the SQL table
     */
    @Override
    public String GetTableName() {
        return getClass().getSimpleName().toLowerCase();
    }

    /**
     * Utility function for combining the table name with the full set of row names & values to be posted to the DB
     *
     * @return A usable string-reference that's used when a new object should be saved that didn't exist originally
     */
    @Override
    public String GetInsertStatement() {
        return String.format("INSERT INTO `%s`.`%s` (%s) VALUES (%s)", GetSchemaName(), GetTableName(), GetColumnNames(), GetValues());
    }

    /**
     * Utility function for combining the table name with the updated row names & values to be posted to the DB
     *
     * @return A usable string-reference that's used when an existing object should be flushed to the database for changes
     */
    @Override
    public String GetUpdateStatement() {
        if (bLoaded) {
            int i = 0;
            StringBuilder sBuilder = new StringBuilder();
            for (String sKey : mCachedUpdate.keySet()) {
                if (i++ != 0) {
                    sBuilder.append(", ");
                }
                sBuilder.append(String.format("`%s` = ?", sKey));
            }
            if (i > 0) {
                return String.format("UPDATE `%s`.`%s` SET %s WHERE %s", GetSchemaName(), GetTableName(), sBuilder.toString(), GetLocationPart());
            }
        }
        return GetInsertStatement();
    }

    /**
     * Compartmentalized function to designate the location in the DB to be stored
     *
     * @return A usable string-reference that contains the location value needed to retrieve our row at the given (auto-inc-key) index
     */
    private String GetLocationPart() {
        String sKeyValue = pKeyValue instanceof String ? String.format("\"%s\"", (""+pKeyValue)) : (""+pKeyValue);
        return String.format("`%s` = %s%s", GetIncrementKey(), sKeyValue, GetAdditionalArguments()).trim();
    }

    /**
     * Utility function for selecting the desired row for our object from the DB
     *
     * @return A usable string-reference that's used when an existing object is created and needs to be loaded
     */
    @Override
    public String GetSelectStatement() {
        return String.format("SELECT %s FROM `%s`.`%s` WHERE %s", GetColumnNames(), GetSchemaName(), GetTableName(), GetLocationPart()).trim();
    }

    /**
     * Gets the class-type to use for the field-values we are storing from this object, in order to properly load
     * the data and also to efficiently store it back to the database potentially
     *
     * @return An ordered list of the java class types used for each field value saved or loaded from this Snapshot
     */
    @Override
    public Class<?>[] GetTableColumnTypes() {
        String[] aColumnNames = GetTableColumnNames();
        Class<?>[] aTypes = new Class<?>[aColumnNames.length];
        int i = 0;
        for (String sColumn : aColumnNames) try {
            Class<?> pColumnType;
            Class<?> pType = getClass().getDeclaredField(sColumn).getType();
            if (pType.isPrimitive()) {
                pColumnType = switch (pType.getSimpleName().toLowerCase()) {
                    case "int" -> Integer.class;
                    case "long" -> Long.class;
                    case "byte" -> Byte.class;
                    case "short" -> Short.class;
                    case "boolean" -> Boolean.class;
                    case "char" -> Character.class;
                    case "float" -> Float.class;
                    case "double" -> Double.class;
                    default -> pType;
                };
            } else {
                if (pType.isArray()) {
                    pColumnType = Array.class;
                } else {
                    pColumnType = pType;
                }
            }
            aTypes[i++] = pColumnType;
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        return aTypes;
    }

    /**
     * Prints out a full dump of all of this object's fields and their values to be used
     *
     * @return A (less-than-efficiently) generated string that can be used for dumping the object's saveable properties
     */
    @Override
    public String ToString() {
        LoadFromSelf(); // Set all of the field values in mSnapshot to the object's current values before logging

        String sHeader =
                "\n------------------------------------------------" +
                "\n::  " + (getClass().getSimpleName()) + " ::" +
                "\n------------------------------------------------\n";
        StringBuilder s = new StringBuilder(sHeader);
        for (String sColumn : GetTableColumnNames()) {
            Object pValue = mSnapshot.get(sColumn);
            if (pValue != null) {
                String sValue;
                if (pValue.getClass() == String.class) {
                    sValue = "\"" + pValue + "\"";
                } else {
                    sValue = "" + pValue;
                }
                s.append(String.format("\t`%s` - %s\n", sColumn, sValue));
            }
        }
        s.append("\n");
        return s.toString();
    }
}
