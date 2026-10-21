///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.memory.SdSpiFull;
import it.uhde.periph.storage.BlockDeviceException;

import java.util.Arrays;

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
public class SdSpiTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        int csGpio = Integer.parseInt(System.getenv().getOrDefault("CS_GPIO", "25"));   // sysfs GPIO number wired to the card's CS

        try (var cs = new SysfsOutputPin(csGpio);                                          // Create CS pin, (gpioNumber=25) → SysfsOutputPin
             var connection = new SPIConnection(bus, dev, 0, 400_000, cs)) {              // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            var sd = new SdSpiFull(connection);                                            // Create SD driver and init the card, (connection) → SdSpiFull
            checkTrue("init", sd.cardType() != SdSpiFull.CardType.NONE);
            var info = sd.info();
            System.out.println("type=" + sd.cardType() + " blocks=" + info.blockCount() + " name=" + info.name());
            checkTrue("geometry", info.blockCount() > 0 && info.blockSize() == 512);
            checkTrue("no_erase_needed", sd.eraseSize() == 512 && !sd.needsErase());
            checkTrue("is_present", sd.isPresent());
            checkTrue("cid_read", sd.cid().length == 16);
            checkTrue("ocr_has_3v3_window", (sd.ocr() & 0x00FF8000) != 0);

            // Scratch area: the last 8 blocks, contents saved and restored.
            long scratch = sd.blockCount() - 8;
            byte[] saved = sd.read(scratch, 8);
            checkTrue("save_scratch", saved.length == 8 * 512);
            byte[] pat = new byte[8 * 512];
            for (int i = 0; i < pat.length; i++) pat[i] = (byte) (i * 7 + 3);

            sd.write(scratch, Arrays.copyOf(pat, 512));
            checkTrue("single_roundtrip", Arrays.equals(sd.read(scratch, 1), Arrays.copyOf(pat, 512)));
            sd.write(scratch, pat);
            sd.sync();
            checkTrue("multi_roundtrip", Arrays.equals(sd.read(scratch, 8), pat));
            try {
                sd.read(sd.blockCount(), 1);
                checkTrue("out_of_range", false);
            } catch (BlockDeviceException e) {
                checkTrue("out_of_range", e.kind() == BlockDeviceException.Kind.OUT_OF_RANGE);
            }

            sd.setCrc(false);
            checkTrue("read_with_crc_off", Arrays.equals(sd.read(scratch, 1), Arrays.copyOf(pat, 512)));
            sd.setCrc(true);
            sd.setSpeed(8_000_000);
            checkTrue("read_at_higher_speed", Arrays.equals(sd.read(scratch, 8), pat));

            sd.write(scratch, saved);
            sd.sync();
            checkTrue("restore_scratch", Arrays.equals(sd.read(scratch, 8), saved));
            System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        }
    }
}
