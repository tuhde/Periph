///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.led.APA102Full

private const val N_PIXELS = 30
private const val FRAME_MS = 16       // ~60 fps
private const val RAINBOW_MS = 10000

fun main() {
    val spiBus    = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val spiDevice = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(spiBus, spiDevice, 0, 1_000_000).use { connection -> // open SPI bus, (busNum, deviceNum, mode, speedHz) → SPIConnection
        val strip = APA102Full(connection, N_PIXELS)                   // construct driver, (connection, n) → APA102Full

        // --- 13-bit effective color depth demonstration ---
        // First pass: full hardware brightness (31) for maximum drive current
        // Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

        // --- Pass 1: Full hardware brightness (31) ---
        // Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
        // hardware current control = 13-bit effective depth per channel.
        strip.brightness = 255                                         // Set global software brightness, (value=0–255) → Unit
        var hueOffset = 0.0
        val start = System.currentTimeMillis()
        var lastPrint = start
        while (System.currentTimeMillis() - start < RAINBOW_MS) {
            for (i in 0 until N_PIXELS) {
                val h = (hueOffset + i.toDouble() / N_PIXELS) % 1.0
                val (r, g, b) = hsvToRgb(h, 1.0, 1.0)
                strip.setPixel(i, r, g, b, 31)                         // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → Unit
            }
            strip.show()                                               // Transmit buffer to strip, () → Unit
                                                                       // applies software brightness scaling then calls connection.write()
            hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2.0)) % 1.0
            val now = System.currentTimeMillis()
            if (now - lastPrint >= 1000) {
                println("rainbow hw_brightness=31 hue_offset=%.3f".format(hueOffset))
                lastPrint = now
            }
            val elapsed = System.currentTimeMillis() - now
            if (elapsed < FRAME_MS) Thread.sleep(FRAME_MS - elapsed.toLong())
        }

        // --- Pass 2: Low hardware brightness (1) ---
        // Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
        // Demonstrates hardware current control vs software brightness scaling.
        strip.brightness = 255                                         // Set global software brightness, (value=0–255) → Unit
        hueOffset = 0.0
        val start = System.currentTimeMillis()
        lastPrint = start
        while (System.currentTimeMillis() - start < RAINBOW_MS) {
            for (i in 0 until N_PIXELS) {
                val h = (hueOffset + i.toDouble() / N_PIXELS) % 1.0
                val (r, g, b) = hsvToRgb(h, 1.0, 1.0)
                strip.setPixel(i, r, g, b, 1)                          // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → Unit
            }
            strip.show()                                               // Transmit buffer to strip, () → Unit
                                                                       // applies software brightness scaling then calls connection.write()
            hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2.0)) % 1.0
            val now = System.currentTimeMillis()
            if (now - lastPrint >= 1000) {
                println("rainbow hw_brightness=1 hue_offset=%.3f".format(hueOffset))
                lastPrint = now
            }
            val elapsed = System.currentTimeMillis() - now
            if (elapsed < FRAME_MS) Thread.sleep(FRAME_MS - elapsed.toLong())
        }

        strip.off()                                                    // Turn off all pixels, () → Unit
        Thread.sleep(1000)
    }
}

private fun hsvToRgb(h: Double, s: Double, v: Double): Triple<Int, Int, Int> {
    if (s == 0.0) {
        val c = (v * 255).toInt()
        return Triple(c, c, c)
    }
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