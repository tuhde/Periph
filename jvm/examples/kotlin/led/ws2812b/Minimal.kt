///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.WS2812BMinimal

fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toInt()     ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toInt()  ?: 0
    val pixelCount = System.getenv("PIXEL_COUNT")?.toInt() ?: 4
    NeoPixelTransport(spiBus, spiDevice).use { transport ->  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
        val strip = WS2812BMinimal(transport, pixelCount)       // construct driver, (transport, n) → WS2812BMinimal

        strip.fill(255, 0, 0)    // fill strip red, (r=0–255, g=0–255, b=0–255) → Unit
        Thread.sleep(1000)
        strip.fill(0, 255, 0)    // fill strip green, (r=0–255, g=0–255, b=0–255) → Unit
        Thread.sleep(1000)
        strip.fill(0, 0, 255)    // fill strip blue, (r=0–255, g=0–255, b=0–255) → Unit
        Thread.sleep(1000)
        strip.off()              // turn off all pixels, () → Unit
    }

}
