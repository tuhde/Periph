///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.memory.SdSpiMinimal;

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        int csGpio = Integer.parseInt(System.getenv().getOrDefault("CS_GPIO", "25"));   // sysfs GPIO number wired to the card's CS

        try (var cs = new SysfsOutputPin(csGpio);                                          // Create CS pin, (gpioNumber=25) → SysfsOutputPin
             var connection = new SPIConnection(bus, dev, 0, 400_000, cs)) {              // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            var sd = new SdSpiMinimal(connection);                                         // Create SD driver and init the card, (connection) → SdSpiMinimal

            System.out.printf("capacity: %d blocks = %d MiB%n", sd.blockCount(), sd.blockCount() / 2048);   // Capacity in 512-byte blocks, () → long
            for (int i = 0; i < 5; i++) {
                byte[] block = sd.readBlock(0);                                            // Read one 512-byte block, (block) → byte[]
                System.out.printf("block 0: %02X %02X %02X %02X ... signature %02X%02X%n",
                    block[0], block[1], block[2], block[3], block[510], block[511]);
                Thread.sleep(2000);
            }
        }
    }
}
