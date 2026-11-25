///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Sram23x1024Minimal

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val sram = Sram23x1024Minimal(connection)                                // Create 23X1024 driver, (connection) → Sram23x1024Minimal

        for (counter in 0 until 5) {
            sram.writeByte(0x00000, counter)                                     // Write one byte, (address 0–131071, value 0–255) → Unit
            val value = sram.readByte(0x00000)                                   // Read one byte, (address 0–131071) → Int
            println("wrote 0x%02X, read 0x%02X".format(counter, value))
            Thread.sleep(500)
        }
    }
}
