///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.NeoPixelConnection
import it.uhde.periph.chips.led.WS2814Full

private const val FRAME_MS  = 33L   // ~30 fps
private const val RAINBOW_S = 5
private const val FLASH_S   = 2
private const val DIM_S     = 2

/**
 * Begins with 5 seconds of rainbow rotation (RGB channels, w=0 per pixel)
 * at ~30 fps, then flashes warm white (r=255, g=200, b=150, w=255) at full
 * brightness for 2 seconds, then dims the warm white to 50% using the
 * brightness property and holds for 2 seconds, then cycles to cool white
 * (r=200, g=210, b=255, w=255). Prints the current mode and brightness
 * once per second. This exercises the white channel, brightness property,
 * per-pixel RGBW addressing, and HSV convenience method, and demonstrates
 * that the WS2814's RGBW order requires no reorder compared to the
 * SK6812RGBW.
 */
fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toInt()     ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toInt()  ?: 0
    val PIXELS     = System.getenv("PIXEL_COUNT")?.toInt() ?: 30
    NeoPixelConnection(spiBus, spiDevice).use { connection ->  // open SPI bus, (busNum, deviceNum) → NeoPixelConnection
        val strip = WS2814Full(connection, PIXELS)                   // construct driver, (connection, n) → WS2814Full

        // --- Rainbow rotation using RGB channels (white=0). Each pixel is
        //     assigned a hue offset by its position; the offset advances each
        //     frame so the rainbow rotates continuously around the strip.
        //     Demonstrates that WS2814's RGBW wire order is identity (R, G, B, W
        //     with no reorder), unlike the SK6812RGBW's GRBW order. Runs at
        //     ~30 fps for 5 seconds. ---
        var hueOffset = 0.0
        val rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L
        var lastPrint  = System.currentTimeMillis()

        while (System.currentTimeMillis() < rainbowEnd) {
            val colors = Array(PIXELS) { p ->
                val hue = (hueOffset + p.toDouble() / PIXELS) % 1.0
                val (r, g, b) = hsvToRgb(hue, 1.0, 1.0)
                intArrayOf(r, g, b, 0)
            }
            strip.setPixels(colors)                                   // load rainbow frame into buffer (w=0), (colors: Array<IntArray[r,g,b,w]>) → Unit
            strip.show()                                              // transmit frame to strip, () → Unit
            hueOffset = (hueOffset + 1.0 / (PIXELS * 2)) % 1.0
            if (System.currentTimeMillis() - lastPrint >= 1000) {
                println("mode=rainbow brightness=${strip.brightness}")
                lastPrint = System.currentTimeMillis()
            }
            Thread.sleep(FRAME_MS)
        }

        // --- Warm white at full brightness for 2 seconds. r=255, g=200, b=150,
        //     w=255 blends the dedicated white element with amber-tinted RGB,
        //     exercising the white channel and the 32-bit RGBW pixel word at
        //     full brightness. ---
        strip.fill(255, 200, 150, 255)                                // fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → Unit
        val flashEnd = System.currentTimeMillis() + FLASH_S * 1000L
        while (System.currentTimeMillis() < flashEnd) {
            println("mode=warm-white brightness=${strip.brightness}")
            Thread.sleep(100)
        }

        // --- Dim warm white to 50% using the brightness property and hold for
        //     2 seconds. Demonstrates that brightness scaling is non-destructive:
        //     the stored RGBW values are unchanged, only the scale factor applied
        //     at show() time changes. ---
        strip.brightness = 128                                        // set global brightness, (value=0–255) → Unit
        strip.show()                                                  // transmit buffer to strip, () → Unit
        val dimEnd = System.currentTimeMillis() + DIM_S * 1000L
        while (System.currentTimeMillis() < dimEnd) {
            println("mode=warm-white-dimmed brightness=${strip.brightness}")
            Thread.sleep(100)
        }

        // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
        //     w=255 shifts the blend toward blue, showcasing the dedicated
        //     white element paired with a cool-tinted RGB base. ---
        strip.brightness = 255                                        // set global brightness, (value=0–255) → Unit
        strip.fill(200, 210, 255, 255)                                // fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → Unit
        println("mode=cool-white brightness=${strip.brightness}")
    }
}

private fun hsvToRgb(h: Double, s: Double, v: Double): Triple<Int, Int, Int> {
    if (s == 0.0) { val c = (v * 255).toInt(); return Triple(c, c, c) }
    val i  = (h * 6.0).toInt()
    val f  = h * 6.0 - i
    val p  = (v * (1.0 - s) * 255).toInt()
    val q  = (v * (1.0 - s * f) * 255).toInt()
    val t  = (v * (1.0 - s * (1.0 - f)) * 255).toInt()
    val vv = (v * 255).toInt()
    return when (i % 6) {
        0    -> Triple(vv, t, p)
        1    -> Triple(q, vv, p)
        2    -> Triple(p, vv, t)
        3    -> Triple(p, q, vv)
        4    -> Triple(t, p, vv)
        else -> Triple(vv, p, q)
    }
}
