///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.WS2812BFull

private const val FRAME_MS  = 33L   // ~30 fps
private const val RAINBOW_S = 10
private const val STROBE_S  = 2
private const val STROBE_HZ = 10

fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toInt()     ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toInt()  ?: 0
    val PIXELS     = System.getenv("PIXEL_COUNT")?.toInt() ?: 4
    NeoPixelTransport(spiBus, spiDevice).use { transport ->  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
        val strip = WS2812BFull(transport, PIXELS)                  // construct driver, (transport, n) → WS2812BFull

        // --- Rainbow rotation for 10 seconds ---
        // Each pixel gets a hue offset by (pixel_index / n_pixels) of the colour
        // wheel, and the hue offset advances by 1/300 each frame so the rainbow
        // rotates at about one revolution per second.
        strip.brightness = 200                                       // dim to ~78% for rainbow, (value=0–255) → Unit
        var hueOffset = 0.0
        val rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L
        var lastPrint  = System.currentTimeMillis()

        while (System.currentTimeMillis() < rainbowEnd) {
            val colors = List(PIXELS) { p ->
                val hue = (hueOffset + p.toDouble() / PIXELS) % 1.0
                hsvToRgb(hue, 1.0, 1.0)
            }
            strip.setPixels(colors)                                   // load rainbow frame into buffer, (colors: List<IntArray[r,g,b]>) → Unit
            strip.show()                                              // transmit frame to strip, () → Unit

            hueOffset = (hueOffset + 1.0 / 300) % 1.0

            if (System.currentTimeMillis() - lastPrint >= 1000) {
                println("hue offset: %.3f".format(hueOffset))
                lastPrint = System.currentTimeMillis()
            }
            Thread.sleep(FRAME_MS)
        }

        // --- Strobe: full white / off at 10 Hz for 2 seconds ---
        // A fast on/off flash tests the brightness property and demonstrates
        // fill() as an immediate update path distinct from setPixel + show().
        strip.brightness = 255                                        // restore full brightness for strobe, (value=0–255) → Unit
        val strobeEnd    = System.currentTimeMillis() + STROBE_S * 1000L
        val halfPeriodMs = 1000L / (STROBE_HZ * 2)

        while (System.currentTimeMillis() < strobeEnd) {
            strip.fill(255, 255, 255)                                 // flash white, (r=0–255, g=0–255, b=0–255) → Unit
            Thread.sleep(halfPeriodMs)
            strip.off()                                               // flash off, () → Unit
            Thread.sleep(halfPeriodMs)
        }

        // --- Return to rainbow ---
        // Resume from the hue offset where the first phase stopped.
        strip.brightness = 200                                        // dim for second rainbow phase, (value=0–255) → Unit
        val resumeEnd = System.currentTimeMillis() + RAINBOW_S * 1000L

        while (System.currentTimeMillis() < resumeEnd) {
            val colors = List(PIXELS) { p ->
                val hue = (hueOffset + p.toDouble() / PIXELS) % 1.0
                hsvToRgb(hue, 1.0, 1.0)
            }
            strip.setPixels(colors)                                   // load rainbow frame into buffer, (colors: List<IntArray[r,g,b]>) → Unit
            strip.show()                                              // transmit frame to strip, () → Unit
            hueOffset = (hueOffset + 1.0 / 300) % 1.0
            Thread.sleep(FRAME_MS)
        }

        strip.off()                                                    // turn off strip at exit, () → Unit
    }

}

private fun hsvToRgb(h: Double, s: Double, v: Double): IntArray {
    if (s == 0.0) { val c = (v * 255).toInt(); return intArrayOf(c, c, c) }
    val i  = (h * 6.0).toInt()
    val f  = h * 6.0 - i
    val p  = (v * (1.0 - s) * 255).toInt()
    val q  = (v * (1.0 - s * f) * 255).toInt()
    val t  = (v * (1.0 - s * (1.0 - f)) * 255).toInt()
    val vv = (v * 255).toInt()
    return when (i % 6) {
    0    -> intArrayOf(vv, t, p)
    1    -> intArrayOf(q, vv, p)
    2    -> intArrayOf(p, vv, t)
    3    -> intArrayOf(p, q, vv)
    4    -> intArrayOf(t, p, vv)
    else -> intArrayOf(vv, p, q)
    }
}
