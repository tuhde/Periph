///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.SK6812RGBWMinimal

def spiBus     = (System.getenv("SPI_BUS")     ?: "0").toInteger()
def spiDevice  = (System.getenv("SPI_DEVICE")  ?: "0").toInteger()
def pixelCount = (System.getenv("PIXEL_COUNT") ?: "4").toInteger()
def transport = new NeoPixelTransport(spiBus, spiDevice)  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
try {
    def strip = new SK6812RGBWMinimal(transport, pixelCount)  // construct driver, (transport, n) → SK6812RGBWMinimal

    strip.fill(255, 0, 0, 0)    // fill strip red, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    Thread.sleep(1000)
    strip.fill(0, 255, 0, 0)    // fill strip green, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    Thread.sleep(1000)
    strip.fill(0, 0, 255, 0)    // fill strip blue, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    Thread.sleep(1000)
    strip.fill(0, 0, 0, 255)    // fill strip white (W channel), (r=0–255, g=0–255, b=0–255, w=0–255) → void
    Thread.sleep(1000)
    strip.off()                 // turn off all pixels, () → void
} finally {
    transport.close()
}
