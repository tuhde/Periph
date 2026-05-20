///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.SK6812RGBWFull

private const val FRAME_MS  = 33L
private const val RAINBOW_S = 10
private const val WARM_S    = 2
private const val WARM_HALF = 100L

fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toInt()     ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toInt()  ?: 0
    val PIXELS     = System.getenv("PIXEL_COUNT")?.toInt() ?: 4
    NeoPixelTransport(spiBus, spiDevice).use { transport ->  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
        val strip = SK6812RGBWFull(transport, PIXELS)               // construct driver, (transport, n) → SK6812RGBWFull

        // --- Rainbow rotation for 10 seconds ---
        // Each pixel gets a hue offset by (pixel_index / n_pixels) of the colour
        // wheel, and the hue offset advances each frame so the rainbow rotates.
        // RGB channels only (w=0); the dedicated white element stays off.
        strip.brightness = 200                                       // dim to ~78% for rainbow, (value=0–255) → Unit
        var hueOffset = 0.0
        val rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L

        while (System.currentTimeMillis() < rainbowEnd) {
            val colors = List(PIXELS) { p ->
                val hue = (hueOffset + p.toDouble() / PIXELS) % 1.0
                val (r, g, b) = hsvToRgb(hue, 1.0, 1.0)
                intArrayOf(r, g, b, 0)
            }
            strip.setPixels(colors)                                   // load rainbow frame into buffer (w=0), (colors: List<IntArray[r,g,b,w]>) → Unit
            strip.show()                                              // transmit frame to strip, () → Unit
            hueOffset = (hueOffset + 1.0 / 300) % 1.0
            Thread.sleep(FRAME_MS)
        }

        // --- Warm-white strobe: showcases the dedicated white element.
        //     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
        //     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
        //     difference between mixed-RGB white and the native W element. ---
        strip.brightness = 255                                        // restore full brightness, (value=0–255) → Unit
        val warmEnd = System.currentTimeMillis() + WARM_S * 1000L

        while (System.currentTimeMillis() < warmEnd) {
            strip.fill(255, 200, 150, 255)                            // flash warm white (RGB+W), (r=0–255, g=0–255, b=0–255, w=0–255) → Unit
            Thread.sleep(WARM_HALF)
            strip.off()                                               // flash off, () → Unit
            Thread.sleep(WARM_HALF)
        }

        // --- Return to rainbow ---
        strip.brightness = 200                                        // dim for second rainbow phase, (value=0–255) → Unit
        val resumeEnd = System.currentTimeMillis() + RAINBOW_S * 1000L

        while (System.currentTimeMillis() < resumeEnd) {
            val colors = List(PIXELS) { p ->
                val hue = (hueOffset + p.toDouble() / PIXELS) % 1.0
                val (r, g, b) = hsvToRgb(hue, 1.0, 1.0)
                intArrayOf(r, g, b, 0)
            }
            strip.setPixels(colors)                                   // load rainbow frame into buffer (w=0), (colors: List<IntArray[r,g,b,w]>) → Unit
            strip.show()                                              // transmit frame to strip, () → Unit
            hueOffset = (hueOffset + 1.0 / 300) % 1.0
            Thread.sleep(FRAME_MS)
        }

        strip.off()                                                    // turn off strip at exit, () → Unit
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
