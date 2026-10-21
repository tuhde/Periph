///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.memory.Sram23x1024Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var sram = new Sram23x1024Minimal(connection);                        // Create 23X1024 driver, (connection) → Sram23x1024Minimal

            for (int counter = 0; counter < 5; counter++) {
                sram.writeByte(0x00000, counter);                                 // Write one byte, (address 0–131071, value 0–255) → void
                int value = sram.readByte(0x00000);                               // Read one byte, (address 0–131071) → int
                System.out.printf("wrote 0x%02X, read 0x%02X%n", counter, value);
                Thread.sleep(500);
            }
        }
    }
}
