import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.LogManager;
import org.apache.bookkeeper.client.AsyncCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import static org.junit.Assert.assertEquals;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class BookKeeperWriteSynchClientsTest {

    static {
        LogManager.getLogManager().reset();
        Logger.getRootLogger().setLevel(Level.INFO);
    }

    private static final byte[] TEST_DATA = new byte[35 * 1024];
    private static final int TESTSIZE = 1000;
    private static final int clientwriters = 10;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder(new File("target").getAbsoluteFile());

    @Test
    public void test() throws Exception {
        try (ZKTestEnv env = new ZKTestEnv(tmp.newFolder("zk").toPath());) {
            env.startBookie();
            ClientConfiguration clientConfiguration = new ClientConfiguration();

            // this is like (sleep(1)) in the loop below
            clientConfiguration.setThrottleValue(0);

            clientConfiguration.setZkServers(env.getAddress());

            // this reduce most of GC
            clientConfiguration.setUseV2WireProtocol(true);

            try (BookKeeper bk = new BookKeeper(clientConfiguration);) {

                for (int j = 0; j < 1000; j++) {

                    LongAdder totalTime = new LongAdder();
                    long _start = System.currentTimeMillis();

                    AtomicInteger totalDone = new AtomicInteger();

                    Map<String, AtomicInteger> numMessagesPerClient = new ConcurrentHashMap<>();

                    Thread[] clients = new Thread[clientwriters];
                    for (int tname = 0; tname < clients.length; tname++) {
                        final String name = "client-" + tname;
                        final int _tname = tname;
                        final AtomicInteger counter = new AtomicInteger();
                        numMessagesPerClient.put(name, counter);
                        Thread tr = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try (
                                    LedgerHandle lh = bk.createLedger(1, 1, 1, BookKeeper.DigestType.CRC32, new byte[0])) {
                                    for (int i = 0; i < TESTSIZE / clientwriters; i++) {
                                        writeData(_tname, lh, counter, totalTime);
                                        totalDone.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    t.printStackTrace();
                                }
                            }
                        }, name);
                        clients[tname] = tr;
                    }
                    for (Thread t : clients) {
                        t.start();
                    }

                    for (Thread t : clients) {
                        t.join();
                    }

                    for (Map.Entry<String, AtomicInteger> entry : numMessagesPerClient.entrySet()) {
                        assertEquals("bad count for " + entry.getKey(), TESTSIZE / clientwriters, entry.getValue().get());
                    }
                    assertEquals(TESTSIZE, totalDone.get());

                    long _stop = System.currentTimeMillis();
                    double delta = _stop - _start;
                    System.out.printf("#" + j + " Wall clock time: " + delta + " ms, "
                        // + "total callbacks time: " + totalTime.sum() + " ms, "
                        + "size %.3f MB -> %.2f ms per entry (latency),"
                        + "%.1f ms per entry (throughput) %.1f MB/s throughput%n",
                        (TEST_DATA.length / (1024 * 1024d)),
                        (totalTime.sum() * 1d / TESTSIZE),
                        (delta / TESTSIZE),
                        ((((TESTSIZE * TEST_DATA.length) / (1024 * 1024d))) / (delta / 1000d)));

                }
            }

        }
    }

    private void writeData(int tname, LedgerHandle lh, AtomicInteger counter, LongAdder totalTime) throws InterruptedException, ExecutionException {
        CompletableFuture cf = new CompletableFuture();
        lh.asyncAddEntry(TEST_DATA, new AsyncCallback.AddCallback() {

            long start = System.currentTimeMillis();

            @Override
            public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
                int actualCount = counter.incrementAndGet();
                long now = System.currentTimeMillis();
                CompletableFuture _cf = (CompletableFuture) ctx;
                if (rc == BKException.Code.OK) {
                    _cf.complete("");
                } else {
                    _cf.completeExceptionally(BKException.create(rc));
                }
                totalTime.add(now - start);
            }
        }, cf);
        cf.get();

    }
}
