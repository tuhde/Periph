///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.memory.SdSpiFull;
import it.uhde.periph.storage.BlockInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        int csGpio = Integer.parseInt(System.getenv().getOrDefault("CS_GPIO", "25"));   // sysfs GPIO number wired to the card's CS

        try (var cs = new SysfsOutputPin(csGpio);                                          // Create CS pin, (gpioNumber=25) → SysfsOutputPin
             var connection = new SPIConnection(bus, dev, 0, 400_000, cs)) {              // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            // --- Bring up the card ---
            // The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
            // low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz during
            // init and is raised to 12 MHz afterwards.
            var sd = new SdSpiFull(connection);                                            // Create SD driver and init the card, (connection) → SdSpiFull
            BlockInfo info = sd.info();                                                    // Geometry and capabilities, () → BlockInfo
            System.out.printf("card %s, %d MiB%n", info.name(), info.blockCount() / 2048);

            // --- Log samples to a raw block range (filesystem-free data logger) ---
            // Each 512-byte block holds a header (sequence number, tick) and 126 samples.
            // The last blocks of the card are used and restored afterwards, so a card that
            // carries a filesystem keeps its data (only the tail of the volume is touched).
            final int blocks = 64;
            long first = sd.blockCount() - blocks;
            byte[] saved = sd.read(first, blocks);                                         // Read blocks, (block, count) → byte[]
            long t0 = System.nanoTime();
            for (int seq = 0; seq < blocks; seq++) {
                ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(seq).putInt((int) ((System.nanoTime() - t0) / 1_000_000));
                for (int i = 2; i < 128; i++) b.putInt(seq * 1000 + i);
                sd.write(first + seq, b.array());                                          // Write blocks, (block, data) → void
            }
            sd.sync();                                                                     // Wait until programming is done, () → void
            System.out.printf("wrote %d blocks in %d ms%n", blocks, (System.nanoTime() - t0) / 1_000_000);

            // --- Verify by reading back ---
            // Read every block back and recompute the expected sample values; any single
            // mismatch (or a CRC16 error thrown by the driver) is counted.
            int bad = 0;
            for (int seq = 0; seq < blocks; seq++) {
                ByteBuffer b = ByteBuffer.wrap(sd.read(first + seq, 1)).order(ByteOrder.LITTLE_ENDIAN);   // Read blocks, (block, count) → byte[]
                boolean ok = b.getInt() == seq;
                b.getInt();
                for (int i = 2; i < 128 && ok; i++) ok = b.getInt() == seq * 1000 + i;
                if (!ok) bad++;
            }
            System.out.println("verify: " + bad + " bad blocks");

            // --- Restore the original contents ---
            sd.write(first, saved);
            sd.sync();
            System.out.println("restored");
        }
    }
}
