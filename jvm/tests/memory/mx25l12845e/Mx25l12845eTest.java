///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

// Hardware test for the MX25L12845E: ids, erase / program / erase-aware write, block erases, BlockDevice,
// block protection and deep power-down. All erases and writes stay inside the last 64 KiB of the chip
// (0xFF0000..0xFFFFFF). Override the wiring with SPI_BUS, SPI_DEVICE.
import it.uhde.periph.chips.memory.Mx25l12845eFull;
import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;

import java.util.Arrays;
import java.util.HexFormat;

public class Mx25l12845eTest {

    static int passed = 0;
    static int failed = 0;
    static final int SCRATCH = 0xFF0000;

    interface Thrower { void run() throws Exception; }

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean throwsKind(Kind kind, Thrower t) {
        try {
            t.run();
        } catch (BlockDeviceException e) {
            return e.kind() == kind;
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        HexFormat hex = HexFormat.ofDelimiter(" ");

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {
            Mx25l12845eFull flash;
            try {
                flash = new Mx25l12845eFull(connection);                            // Release deep power-down, check the JEDEC ID, (connection) → Mx25l12845eFull
                checkTrue("init (JEDEC C2 20 18)", true);
            } catch (Exception e) {
                checkTrue("init (JEDEC C2 20 18)", false);
                System.out.println("init error: " + e.getMessage());
                System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
                return;
            }
            checkTrue("jedec_id", Arrays.equals(flash.jedecId(), new byte[] {(byte) 0xC2, 0x20, 0x18}));
            checkTrue("electronic_id (RES) = 17h", flash.electronicId() == 0x17);
            int[] ids = flash.deviceIds();
            checkTrue("device_ids (REMS) = C2 17", ids[0] == 0xC2 && ids[1] == 0x17);
            int sr = flash.readStatus();
            checkTrue("status: not busy, WEL clear", (sr & 0x03) == 0);
            int bp = flash.protection();
            System.out.printf("INFO status 0x%02X, BP=%d%n", sr, bp);
            System.out.printf("INFO security 0x%02X (bit0 = OTP factory locked)%n", flash.readSecurity());
            byte[] esn = flash.serialNumber();
            System.out.println("INFO ESN: " + hex.formatHex(esn));
            checkTrue("ESN stable", Arrays.equals(esn, flash.serialNumber()));
            checkTrue("SFDP signature", new String(flash.readSfdp(0, 4)).equals("SFDP"));
            if (bp != 0) {
                System.out.println("INFO protection was BP=" + bp + "; clearing for the test");
                flash.setProtection(0);
            }

            flash.eraseSector(SCRATCH);
            checkTrue("erase_sector", true);
            boolean blank = true;
            for (byte b : flash.readBytes(SCRATCH, 4096)) if (b != (byte) 0xFF) blank = false;
            checkTrue("sector blank (all FF) after erase", blank);

            byte[] page = new byte[256];
            for (int i = 0; i < page.length; i++) page[i] = (byte) (i * 3 + 1);
            flash.pageProgram(SCRATCH, page);
            checkTrue("page_program 256 bytes", true);
            checkTrue("read_bytes matches", Arrays.equals(flash.readBytes(SCRATCH, 256), page));
            checkTrue("fast_read matches", Arrays.equals(flash.fastRead(SCRATCH, 256), page));
            flash.pageProgram(SCRATCH, new byte[] {0x0F, (byte) 0xF0, 0x00, (byte) 0xFF});
            byte[] and = flash.readBytes(SCRATCH, 4);
            checkTrue("programming only clears bits (AND)", and[0] == (byte) (page[0] & 0x0F) && and[1] == (byte) (page[1] & 0xF0)
                    && and[2] == 0 && and[3] == page[3]);
            final Mx25l12845eFull f = flash;
            checkTrue("page-crossing program refused", throwsKind(Kind.OUT_OF_RANGE, () -> f.pageProgram(SCRATCH + 250, Arrays.copyOf(page, 10))));

            byte[] data = new byte[3000];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i ^ (i >> 7));
            flash.writeBytes(SCRATCH + 4096 - 100, data);
            checkTrue("write_bytes across two sectors", true);
            checkTrue("read back across sectors", Arrays.equals(flash.readBytes(SCRATCH + 4096 - 100, 3000), data));
            flash.writeBytes(SCRATCH + 4096 - 100, data);
            checkTrue("identical rewrite", true);
            checkTrue("write past the end refused", throwsKind(Kind.OUT_OF_RANGE, () -> f.writeBytes(f.size() - 2, Arrays.copyOf(data, 5))));

            flash.eraseBlock32k(SCRATCH);
            checkTrue("erase_block_32k", true);
            flash.eraseBlock64k(SCRATCH);
            checkTrue("erase_block_64k", true);
            blank = true;
            for (byte b : flash.readBytes(SCRATCH + 0xF000, 16)) if (b != (byte) 0xFF) blank = false;
            checkTrue("64 KiB block blank", blank);

            checkTrue("BlockDevice geometry", flash.blockSize() == 512 && flash.blockCount() == 32768 && flash.eraseSize() == 4096 && flash.needsErase());
            long blk = SCRATCH / 512;
            byte[] w = new byte[1024];
            for (int i = 0; i < w.length; i++) w[i] = (byte) (i * 5 + 2);
            flash.write(blk, w);
            checkTrue("BlockDevice write 2 blocks", true);
            checkTrue("BlockDevice read back", Arrays.equals(flash.read(blk, 2), w));
            checkTrue("0->1 without erase -> NOT_ERASED", throwsKind(Kind.NOT_ERASED, () -> { byte[] ff = new byte[512]; Arrays.fill(ff, (byte) 0xFF); f.write(blk, ff); }));
            flash.erase(blk, 8);
            checkTrue("BlockDevice erase (4 KiB)", true);
            checkTrue("unaligned erase refused", throwsKind(Kind.OUT_OF_RANGE, () -> f.erase(blk + 1, 8)));
            flash.sync();
            checkTrue("sync", true);

            flash.setProtection(1);
            checkTrue("set_protection(1)", flash.protection() == 1);
            checkTrue("program into protected area -> WRITE_PROTECTED", throwsKind(Kind.WRITE_PROTECTED, () -> f.pageProgram(SCRATCH + 0x8000, new byte[] {0})));
            checkTrue("erase of protected sector -> WRITE_PROTECTED", throwsKind(Kind.WRITE_PROTECTED, () -> f.eraseSector(SCRATCH + 0x8000)));
            flash.setProtection(0);
            checkTrue("set_protection(0)", flash.protection() == 0);
            flash.pageProgram(SCRATCH + 0x8000, new byte[] {0});
            checkTrue("program after unprotect", true);

            flash.deepPowerDown();
            checkTrue("deep_power_down", true);
            Thread.sleep(1);
            flash.releaseDeepPowerDown();
            checkTrue("release_deep_power_down, RES again", flash.electronicId() == 0x17);

            flash.eraseBlock64k(SCRATCH);
            flash.waitUntilReady(1000);
            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
    }
}
