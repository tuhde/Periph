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

public class Demo {
    static final int SCRATCH = 0xFF0000;

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        HexFormat hex = HexFormat.ofDelimiter(" ");

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            // --- Bring up the chip ---
            // Plain single-I/O SPI: the constructor releases deep power-down, checks the JEDEC ID and
            // waits until the chip is idle. The serial-number area is the factory ESN on parts that have one.
            var flash = new Mx25l12845eFull(connection);                          // Create MX25L12845E driver and check the ID, (connection) → Mx25l12845eFull
            System.out.println("MX25L12845E, " + flash.size() / (1024 * 1024) + " MiB, serial " + hex.formatHex(flash.serialNumber()));  // Read the serial-number OTP area, () → byte[16]

            // --- Append records to a ring of four sectors ---
            // 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
            // (all 0xFF) needs no erase, so appending is just a page program; only when the writer
            // enters the next sector is that sector erased first - one erase per 128 records.
            final int ring = 0xFFC000, sectors = 4, slotLen = 32, slots = sectors * 4096 / slotLen;
            for (int s = 0; s < sectors; s++) flash.eraseSector(ring + s * 4096);   // Erase one 4 KiB sector, (address) → void
            long t0 = System.nanoTime();
            int erases = 0, slot = 0;
            for (int seq = 0; seq < 300; seq++) {
                if (slot % 128 == 0 && seq >= slots) {                            // wrapped into the next sector: make it blank
                    flash.eraseSector(ring + (slot / 128) * 4096);
                    erases++;
                }
                byte[] rec = new byte[slotLen];
                Arrays.fill(rec, (byte) 0xA5);
                java.nio.ByteBuffer.wrap(rec).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, seq).putInt(4, (int) ((System.nanoTime() - t0) / 1_000_000));
                flash.pageProgram(ring + slot * slotLen, rec);                    // Program one record (inside a page), (address, data) → void
                slot = (slot + 1) % slots;
            }
            System.out.printf("appended 300 records in %d ms, %d sector erases%n", (System.nanoTime() - t0) / 1_000_000, erases);

            // --- Verify by scanning the ring ---
            // Every slot must carry a valid sequence number and the 0xA5 payload.
            int good = 0, newest = 0;
            byte[] all = flash.readBytes(ring, sectors * 4096);                   // Read bytes, (address, length) → byte[]
            var bb = java.nio.ByteBuffer.wrap(all).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int s = 0; s < slots; s++) {
                int seq = bb.getInt(s * slotLen);
                if (seq != -1 && all[s * slotLen + 20] == (byte) 0xA5) {
                    good++;
                    newest = Math.max(newest, seq);
                }
            }
            System.out.printf("ring holds %d valid records, newest sequence number %d%n", good, newest);

            // --- Leave the chip clean ---
            for (int s = 0; s < sectors; s++) flash.eraseSector(ring + s * 4096);
            System.out.println("ring erased");
        }
    }
}
