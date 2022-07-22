import database.Database;
import user.Account;

import java.text.NumberFormat;
import java.util.concurrent.*;

/**
 *
 * @author Smoke
 */
public class ShitakisApp {

    // Test - to create a simulated process that updates a user's values over time
    // Goals - are to verify that saving is always accurate, minimalistic and lock-safe

    public static void main(String[] args) {
        //Create the simulated process executor that will update the user's value as if it was someone in-game
        final ScheduledExecutorService pSetValueExecutor = Executors.newSingleThreadScheduledExecutor();

        //Create the observer executor that will process aggregated updates from the object cache
        final ScheduledExecutorService pUpdateExecutor = Executors.newSingleThreadScheduledExecutor();

        //Init our database connection pool
        Database.Init("127.0.0.1", "3306", "shitakis", "root", "password"); // will have to fill this method yourself

        //Create our Account object for a test
        final Account pAccount = new Account(1);
        // NOTE - when initializing an object that implements Snapshot/SnapshotList,
        //        it loads its own information from the database automatically during instantiation

        pSetValueExecutor.scheduleAtFixedRate(() -> {
            pAccount.nNexonCash += 10000;
            System.out.printf("\r\nAdded 10,000 NX cash (New Total: %s)", NumberFormat.getInstance().format(pAccount.nNexonCash));
        }, 1000L, 1000L, TimeUnit.MILLISECONDS);

        pUpdateExecutor.scheduleAtFixedRate(() -> {
            // Optional logging below if you want to verify your statements are being assembled correctly
            //System.out.println(pAccount.GetInsertStatement());
            System.out.println(pAccount.GetUpdateStatement());

            pAccount.Update();
            // This is ALL that you need to ever do - compares and save only changed values altered since the previous flush and re-caches the history

        }, 1500, 3000L, TimeUnit.MILLISECONDS);

        try {
            System.out.println("Press any key to exit the program.");
            // since we're running the program asynchronously
            int n = System.in.read();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            pSetValueExecutor.shutdown();
            pUpdateExecutor.shutdown();
        }
    }
}
