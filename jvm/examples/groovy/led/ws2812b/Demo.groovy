///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.WS2812BFull

final long   FRAME_MS  = 33    // ~30 fps
final int    RAINBOW_S = 10
final int    STROBE_S  = 2
final int    STROBE_HZ = 10

def spiBus     = (System.getenv("SPI_BUS")     ?: "0").toInteger()
def spiDevice  = (System.getenv("SPI_DEVICE")  ?: "0").toInteger()
def PIXELS     = (System.getenv("PIXEL_COUNT") ?: "4").toInteger()
def transport = new NeoPixelTransport(spiBus, spiDevice)  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
try {
    def strip = new WS2812BFull(transport, PIXELS)              // construct driver, (transport, n) → WS2812BFull

    // --- Rainbow rotation for 10 seconds ---
    // Each pixel gets a hue offset by (pixel_index / n_pixels) of the colour
    // wheel, and the hue offset advances by 1/300 each frame so the rainbow
    // rotates at about one revolution per second.
    strip.setBrightness(200)                                     // dim to ~78% for rainbow, (value=0–255) → void
    double hueOffset = 0.0
    long rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L
    long lastPrint  = System.currentTimeMillis()

    while (System.currentTimeMillis() < rainbowEnd) {
        def colors = (0..<PIXELS).collect { p ->
            double hue = (hueOffset + p / (double) PIXELS) % 1.0
            hsvToRgb(hue, 1.0, 1.0)
        }
        strip.setPixels(colors)                                   // load rainbow frame into buffer, (colors: List<int[]>) → void
        strip.show()                                              // transmit frame to strip, () → void

        hueOffset = (hueOffset + 1.0 / 300) % 1.0

        if (System.currentTimeMillis() - lastPrint >= 1000) {
            println("hue offset: ${String.format('%.3f', hueOffset)}")
            lastPrint = System.currentTimeMillis()
        }
        Thread.sleep(FRAME_MS)
    }

    // --- Strobe: full white / off at 10 Hz for 2 seconds ---
    // A fast on/off flash tests the brightness property and demonstrates
    // fill() as an immediate update path distinct from setPixel + show().
    strip.setBrightness(255)                                      // restore full brightness for strobe, (value=0–255) → void
    long strobeEnd    = System.currentTimeMillis() + STROBE_S * 1000L
    long halfPeriodMs = 1000L / (STROBE_HZ * 2)

    while (System.currentTimeMillis() < strobeEnd) {
        strip.fill(255, 255, 255)                                 // flash white, (r=0–255, g=0–255, b=0–255) → void
        Thread.sleep(halfPeriodMs)
        strip.off()                                               // flash off, () → void
        Thread.sleep(halfPeriodMs)
    }

    // --- Return to rainbow ---
    // Resume from the hue offset where the first phase stopped.
    strip.setBrightness(200)                                      // dim for second rainbow phase, (value=0–255) → void
    long resumeEnd = System.currentTimeMillis() + RAINBOW_S * 1000L

    while (System.currentTimeMillis() < resumeEnd) {
        def colors = (0..<PIXELS).collect { p ->
            double hue = (hueOffset + p / (double) PIXELS) % 1.0
            hsvToRgb(hue, 1.0, 1.0)
        }
        strip.setPixels(colors)                                   // load rainbow frame into buffer, (colors: List<int[]>) → void
        strip.show()                                              // transmit frame to strip, () → void
        hueOffset = (hueOffset + 1.0 / 300) % 1.0
        Thread.sleep(FRAME_MS)
    }

    strip.off()                                                    // turn off strip at exit, () → void

} finally {
    transport.close()
}

static int[] hsvToRgb(double h, double s, double v) {
    if (s == 0.0) { int c = (int)(v * 255); return [c, c, c] as int[] }
    int i    = (int)(h * 6.0)
    double f = h * 6.0 - i
    int p    = (int)(v * (1.0 - s) * 255)
    int q    = (int)(v * (1.0 - s * f) * 255)
    int t    = (int)(v * (1.0 - s * (1.0 - f)) * 255)
    int vv   = (int)(v * 255)
    switch (i % 6) {
        case 0:  return [vv, t, p] as int[]
        case 1:  return [q, vv, p] as int[]
        case 2:  return [p, vv, t] as int[]
        case 3:  return [p, q, vv] as int[]
        case 4:  return [t, p, vv] as int[]
        default: return [vv, p, q] as int[]
    }
}
