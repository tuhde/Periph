///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEVICE.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.memory.Mx25l12845eFull;

import java.util.Arrays;
import java.util.HexFormat;

public class Complete {
    static final int SCRATCH = 0xFF0000;

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        HexFormat hex = HexFormat.ofDelimiter(" ");

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var flash = new Mx25l12845eFull(connection);                          // Create MX25L12845E driver and check the ID, (connection, autoErase=false) → Mx25l12845eFull

            byte[] id = flash.jedecId();                                          // Read JEDEC ID, () → byte[]
                                                                                  // C2 20 18 for the MX25L12845E
            int res = flash.electronicId();                                       // Read the old-style electronic ID (RES), () → int
            int[] ids = flash.deviceIds();                                        // Read manufacturer + device ID (REMS), () → int[2]
            System.out.printf("JEDEC %s, RES %02X, REMS %02X %02X%n", hex.formatHex(id), res, ids[0], ids[1]);

            int sr = flash.readStatus();                                          // Read the status register, () → int
            int bp = flash.protection();                                          // Read BP3:0, () → int
            int scur = flash.readSecurity();                                      // Read the security register, () → int
            System.out.printf("status 0x%02X, BP=%d, security 0x%02X%n", sr, bp, scur);
            System.out.println("ESN: " + hex.formatHex(flash.serialNumber()));    // Read the serial-number OTP area, () → byte[16]
                                                                                  // the factory ESN when security bit 0 is set, blank (FF) otherwise
            System.out.println("SFDP: " + hex.formatHex(flash.readSfdp(0, 16)));  // Read the SFDP table, (address, length) → byte[]
                                                                                  // starts with the signature "SFDP"

            flash.eraseSector(SCRATCH);                                           // Erase one 4 KiB sector, (address) → void
                                                                                  // takes 60 ms typ, 300 ms max
            byte[] page = new byte[256];
            for (int i = 0; i < page.length; i++) page[i] = (byte) i;
            flash.pageProgram(SCRATCH, page);                                     // Program one page (must not cross a page boundary), (address, data) → void
                                                                                  // 1.4 ms typ for 256 bytes; bits only go 1 -> 0
            byte[] back = flash.fastRead(SCRATCH, 256);                           // FAST_READ, (address, length) → byte[]
                                                                                  // same data as readBytes, for clocks above 50 MHz
            System.out.println("page program + fast_read: " + Arrays.equals(page, back));

            flash.eraseBlock32k(SCRATCH);                                         // Erase a 32 KiB block, (address) → void
                                                                                  // 0.5 s typ
            flash.eraseBlock64k(SCRATCH);                                         // Erase a 64 KiB block, (address) → void
                                                                                  // 0.7 s typ
            System.out.println("after block erase: " + hex.formatHex(flash.readBytes(SCRATCH, 16)));   // Read bytes, (address, length) → byte[]
            // chipErase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

            byte[] text = "erase-aware write".getBytes();
            flash.writeBytes(SCRATCH + 100, text);                                // Write bytes, erasing the sector only when needed, (address, data) → void
            flash.writeBytes(SCRATCH + 100, text);                                // the same bytes again: nothing is programmed or erased
            System.out.println("text: " + new String(flash.readBytes(SCRATCH + 100, text.length)));

            flash.deepPowerDown();                                                // Enter deep power-down, () → void
                                                                                  // only RDP / RES are accepted afterwards
            flash.releaseDeepPowerDown();                                         // Leave deep power-down (RDP, waits tRES2), () → void
            System.out.println("busy: " + flash.isBusy());                        // WIP bit, () → boolean
            flash.waitUntilReady(1000);                                           // Wait for WIP = 0, (timeoutMs) → void

            System.out.println(flash.info());                                     // Geometry and capabilities, () → BlockInfo
                                                                                  // 512-byte blocks, 4 KiB erase unit, needsErase
            // setProtection(), writeStatus(), programOtp() and lockOtp() change non-volatile state; see the spec before using them.

            flash.eraseSector(SCRATCH);
            System.out.println("done");
        }
    }
}
