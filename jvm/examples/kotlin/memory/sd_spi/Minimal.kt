///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.memory.SdSpiMinimal

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0
    val csGpio = System.getenv("CS_GPIO")?.toIntOrNull() ?: 25   // sysfs GPIO number wired to the card's CS

    SysfsOutputPin(csGpio).use { cs ->                                                  // Create CS pin, (gpioNumber=25) → SysfsOutputPin
        SPIConnection(bus, dev, 0, 400_000, cs).use { connection ->                     // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            val sd = SdSpiMinimal(connection)                                           // Create SD driver and init the card, (connection) → SdSpiMinimal

            println("capacity: ${sd.blockCount()} blocks = ${sd.blockCount() / 2048} MiB")   // Capacity in 512-byte blocks, () → Long
            repeat(5) {
                val block = sd.readBlock(0)                                             // Read one 512-byte block, (block) → ByteArray
                println("block 0: ${block.take(4).joinToString(" ") { "%02X".format(it) }} ... signature %02X%02X".format(block[510], block[511]))
                Thread.sleep(2000)
            }
        }
    }
}
