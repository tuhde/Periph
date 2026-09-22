///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.led.APA102Minimal

fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0
    val pixelCount = System.getenv("PIXEL_COUNT")?.toIntOrNull() ?: 30

    SPIConnection(spiBus, spiDevice, 0, 1_000_000).use { connection -> // open SPI bus, (busNum, deviceNum, mode, speedHz) → SPIConnection
        val strip = APA102Minimal(connection, pixelCount)              // construct driver, (connection, n) → APA102Minimal

        strip.fill(255, 0, 0)   // fill strip red, (r=0–255, g=0–255, b=0–255) → Unit
        Thread.sleep(1000)
        strip.fill(0, 255, 0)   // fill strip green, (r=0–255, g=0–255, b=0–255) → Unit
        Thread.sleep(1000)
        strip.fill(0, 0, 255)   // fill strip blue, (r=0–255, g=0–255, b=0–255) → Unit
        Thread.sleep(1000)
        strip.off()             // turn off all pixels, () → Unit
    }
}