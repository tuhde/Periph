///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.led.APA102Full

fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0
    val pixelCount = System.getenv("PIXEL_COUNT")?.toIntOrNull() ?: 8

    SPIConnection(spiBus, spiDevice, 0, 1_000_000).use { connection -> // open SPI bus, (busNum, deviceNum, mode, speedHz) → SPIConnection
        val strip = APA102Full(connection, pixelCount)                 // construct driver, (connection, n) → APA102Full

        // fill — set all pixels and send immediately
        strip.fill(255, 0, 0)                                           // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → Unit
                                                                      // stores brightness/B/G/R in buffer and calls connection.write()
        Thread.sleep(500)

        // set individual pixels then show
        strip.setPixel(0, 255, 0, 0, 31)                                // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → Unit
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.setPixel(1, 0, 255, 0, 31)                                // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → Unit
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.setPixel(2, 0, 0, 255, 31)                                // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → Unit
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.show()                                                    // Transmit buffer to strip, () → Unit
                                                                      // applies software brightness scaling then calls connection.write()
        Thread.sleep(500)

        // set_pixels — write multiple pixels at once
        strip.setPixels(listOf(                                          // Set pixels from list of [r,g,b] or [r,g,b,brightness], (colors=List<IntArray>) → Unit
            intArrayOf(255, 128, 0),   intArrayOf(128, 0, 255),   intArrayOf(0, 255, 128),
            intArrayOf(255, 255, 0),   intArrayOf(0, 255, 255),   intArrayOf(255, 0, 255),
            intArrayOf(128, 128, 128), intArrayOf(255, 255, 255)
        ))                                                              // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show()                                                    // Transmit buffer to strip, () → Unit
                                                                      // applies software brightness scaling then calls connection.write()
        Thread.sleep(500)

        // set_pixels with per-pixel hardware brightness
        strip.setPixels(listOf(                                          // Set pixels with varying hardware brightness, (colors=List<IntArray>) → Unit
            intArrayOf(255, 0, 0, 31), intArrayOf(255, 0, 0, 16), intArrayOf(255, 0, 0, 8),  intArrayOf(255, 0, 0, 4),
            intArrayOf(0, 255, 0, 31), intArrayOf(0, 255, 0, 16), intArrayOf(0, 255, 0, 8),  intArrayOf(0, 255, 0, 4)
        ))                                                              // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show()                                                    // Transmit buffer to strip, () → Unit
                                                                      // applies software brightness scaling then calls connection.write()
        Thread.sleep(500)

        // brightness — global software scale applied at show() time
        strip.brightness = 64                                           // Set global software brightness, (value=0–255) → Unit
                                                                      // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
        strip.show()                                                    // Transmit buffer to strip, () → Unit
                                                                      // applies software brightness scaling then calls connection.write()
        Thread.sleep(500)
        strip.brightness = 255                                          // Set global software brightness, (value=0–255) → Unit
                                                                      // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

        // fill_hsv — fill all pixels from HSV colour
        strip.fillHsv(0.0, 1.0, 1.0)                                    // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Unit
                                                                      // converts HSV to RGB then calls fill(); hue 0.0 = red
        Thread.sleep(500)
        strip.fillHsv(0.333, 1.0, 1.0)                                  // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Unit
                                                                      // converts HSV to RGB then calls fill(); hue 0.333 = green
        Thread.sleep(500)
        strip.fillHsv(0.667, 1.0, 1.0)                                  // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Unit
                                                                      // converts HSV to RGB then calls fill(); hue 0.667 = blue
        Thread.sleep(500)

        // rotate — shift pixel buffer left, then show
        strip.setPixels(listOf(                                          // Set pixels from list of [r,g,b], (colors=List<IntArray>) → Unit
            intArrayOf(255, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0),
            intArrayOf(0, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0)
        ))                                                              // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show()                                                    // Transmit buffer to strip, () → Unit
                                                                      // applies software brightness scaling then calls connection.write()
        Thread.sleep(500)
        repeat(7) {
            strip.rotate(1)                                             // Rotate pixel buffer left, (steps=1) → Unit
                                                                      // shifts buffer by steps pixel positions; wraps around; does not send
            strip.show()                                                // Transmit buffer to strip, () → Unit
                                                                      // applies software brightness scaling then calls connection.write()
            Thread.sleep(200)
        }

        strip.off()                                                     // Turn off all pixels, () → Unit
                                                                      // equivalent to fill(0, 0, 0)
        Thread.sleep(1000)
    }
}