///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.accelerometer.Adxl362Minimal

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val chip = Adxl362Minimal(connection)                                    // Create ADXL362 driver, (connection) → Adxl362Minimal

        repeat(5) {
            val xyz = chip.read()                                                // Read 3-axis acceleration, () → DoubleArray g
            println("x=${xyz[0].format("+%.3f")}  y=${xyz[1].format("+%.3f")}  z=${xyz[2].format("+%.3f")} g")
            Thread.sleep(100)
        }
    }
}