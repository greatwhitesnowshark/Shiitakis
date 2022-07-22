package user;

import database.Database;
import database.Snapshot;

public class Account extends Snapshot {

    public int dwAccountID, nNexonCash;
    public String sUsername = "";

    public Account(String sNexonClubID) {
        super(sNexonClubID, true, false);
    }

    public Account(int dwAccountID) {
        super(dwAccountID, true, false);
        if (dwAccountID > 0) {
            this.bLoaded = LoadFromDB();
        }
    }

    @Override
    public String GetIncrementKey() {
        return "dwAccountID";
    }

    @Override
    public String GetSchemaName() {
        return Database.LOGIN_SCHEMA; // will have to fill this constant yourself
    }

    @Override
    public String[] GetTableColumnNames() {
        return new String[] {
                "dwAccountID",
                "sUsername",
                "nNexonCash"
        };
    }
}
