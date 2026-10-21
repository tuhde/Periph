///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Sram23x1024Minimal

int bus = System.getenv("SPI_BUS")?.toInteger() ?: 0
int dev = System.getenv("SPI_DEVICE")?.toInteger() ?: 0

try (SPIConnection connection = new SPIConnection(bus, dev, 0, 8_000_000)) {    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
    Sram23x1024Minimal sram = new Sram23x1024Minimal(connection)               // Create 23X1024 driver, (connection) → Sram23x1024Minimal

    5.times { int counter ->
        sram.writeByte(0x00000, counter)                                        // Write one byte, (address 0–131071, value 0–255) → void
        int value = sram.readByte(0x00000)                                      // Read one byte, (address 0–131071) → int
        println String.format("wrote 0x%02X, read 0x%02X", counter, value)
        Thread.sleep(500)
    }
}
