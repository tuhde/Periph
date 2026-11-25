///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.memory.Sram23x1024Full;

import java.util.Arrays;

public class Sram23x1024Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var sram = new Sram23x1024Full(connection);                           // Create 23X1024 Full driver, (connection) → Sram23x1024Full
            checkTrue("init_mode_roundtrip", true);                               // constructor writes MODE=0x40 and verifies it with RDMR
            checkTrue("size", sram.size() == 131072);
            checkTrue("default_mode_sequential", sram.getMode() == Sram23x1024Full.MODE_SEQUENTIAL);

            sram.writeByte(0x00000, 0xA5);
            checkTrue("byte_roundtrip_first", sram.readByte(0x00000) == 0xA5);
            sram.writeByte(0x1FFFF, 0x5A);
            checkTrue("byte_roundtrip_last", sram.readByte(0x1FFFF) == 0x5A);
            sram.writeByte(0x10000, 0x3C);
            checkTrue("address_bit16_distinct", sram.readByte(0x10000) == 0x3C && sram.readByte(0x00000) == 0xA5);

            byte[] pattern = new byte[1000];
            for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 7 + 3);
            sram.write(0x00400, pattern);
            checkTrue("block_roundtrip_chunked", Arrays.equals(sram.read(0x00400, 1000), pattern));

            sram.fill(0x02000, 100, 0xC3);
            byte[] filled = sram.read(0x02000, 100);
            boolean allC3 = true;
            for (byte b : filled) allC3 &= (b & 0xFF) == 0xC3;
            checkTrue("fill_range", allC3 && sram.readByte(0x02064) != 0xC3);

            sram.write(0x1FFF0, Arrays.copyOf(pattern, 16));
            checkTrue("block_at_array_end", Arrays.equals(sram.read(0x1FFF0, 16), Arrays.copyOf(pattern, 16)));

            sram.setMode(Sram23x1024Full.MODE_PAGE);
            checkTrue("set_mode_page", sram.getMode() == Sram23x1024Full.MODE_PAGE);
            sram.write(0x04010, Arrays.copyOf(pattern, 64));                      // crosses the pages at 0x4020 and 0x4040
            checkTrue("page_mode_crossing_linear", Arrays.equals(sram.read(0x04010, 64), Arrays.copyOf(pattern, 64)));

            sram.setMode(Sram23x1024Full.MODE_BYTE);
            checkTrue("set_mode_byte", sram.getMode() == Sram23x1024Full.MODE_BYTE);
            sram.write(0x05000, Arrays.copyOf(pattern, 8));
            checkTrue("byte_mode_block", Arrays.equals(sram.read(0x05000, 8), Arrays.copyOf(pattern, 8)));

            sram.setMode(Sram23x1024Full.MODE_SEQUENTIAL);
            checkTrue("set_mode_sequential", sram.getMode() == Sram23x1024Full.MODE_SEQUENTIAL);

            sram.resetIo();                                                       // Return to SPI mode, () → void
            checkTrue("reset_io_stays_spi", sram.getMode() == Sram23x1024Full.MODE_SEQUENTIAL);
            checkTrue("data_survives_reset_io", sram.readByte(0x00000) == 0xA5);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
