///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEVICE.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.memory.Mx25l12845eMinimal;

import java.util.Arrays;
import java.util.HexFormat;

public class Minimal {
    static final int SCRATCH = 0xFF0000;

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        HexFormat hex = HexFormat.ofDelimiter(" ");

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var flash = new Mx25l12845eMinimal(connection);                       // Create MX25L12845E driver and check the ID, (connection) → Mx25l12845eMinimal

            System.out.println("JEDEC ID " + hex.formatHex(flash.jedecId()) + ", " + flash.size() / (1024 * 1024) + " MiB");  // Read JEDEC ID, () → byte[]; capacity in bytes, () → int
            for (int counter = 0; counter < 10; counter++) {
                flash.writeBytes(SCRATCH, new byte[] {(byte) counter});           // Write bytes (erases the sector when needed), (address, data) → void
                int value = flash.readBytes(SCRATCH, 1)[0] & 0xFF;                // Read bytes, (address, length) → byte[]
                System.out.printf("wrote 0x%02X, read 0x%02X%n", counter, value);
                Thread.sleep(500);
            }
            flash.eraseSector(SCRATCH);                                           // Erase one 4 KiB sector, (address) → void
            System.out.println("done, scratch sector erased");
        }
    }
}
