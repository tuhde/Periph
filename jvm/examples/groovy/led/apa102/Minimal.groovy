///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.led.APA102Minimal

def spiBus     = System.getenv("SPI_BUS")?.toInteger() ?: 0
def spiDevice  = System.getenv("SPI_DEVICE")?.toInteger() ?: 0
def pixelCount = System.getenv("PIXEL_COUNT")?.toInteger() ?: 30

def connection = new SPIConnection(spiBus, spiDevice, 0, 1_000_000)
def strip = new APA102Minimal(connection, pixelCount)

try {
    strip.fill(255, 0, 0)   // fill strip red, (r=0–255, g=0–255, b=0–255) → void
    Thread.sleep(1000)
    strip.fill(0, 255, 0)   // fill strip green, (r=0–255, g=0–255, b=0–255) → void
    Thread.sleep(1000)
    strip.fill(0, 0, 255)   // fill strip blue, (r=0–255, g=0–255, b=0–255) → void
    Thread.sleep(1000)
    strip.off()             // turn off all pixels, () → void
} finally {
    connection.close()
}