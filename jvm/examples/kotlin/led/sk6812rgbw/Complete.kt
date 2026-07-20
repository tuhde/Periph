///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.SK6812RGBWFull

fun main() {
    val spiBus     = System.getenv("SPI_BUS")?.toInt()     ?: 0
    val spiDevice  = System.getenv("SPI_DEVICE")?.toInt()  ?: 0
    val pixelCount = System.getenv("PIXEL_COUNT")?.toInt() ?: 4
    NeoPixelTransport(spiBus, spiDevice).use { transport ->  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
        val strip = SK6812RGBWFull(transport, pixelCount)       // construct driver, (transport, n) → SK6812RGBWFull

        strip.fill(255, 0, 0, 0)                                          // fill entire strip red and send, (r=0–255, g=0–255, b=0–255, w=0–255) → Unit
                                                                           // inherited from SK6812RGBWMinimal; fills buffer in GRBW order and calls transport.write()
        Thread.sleep(500)

        strip.fill(0, 0, 0, 255)                                          // fill strip using W channel, (r=0–255, g=0–255, b=0–255, w=0–255) → Unit
                                                                           // w=255 activates dedicated white LED element; RGB channels stay dark
        Thread.sleep(500)

        strip.brightness = 128                                             // set brightness to 50%, (value=0–255)
                                                                           // applied non-destructively at show() time: sent = stored × brightness / 255
        strip.fill(255, 200, 150, 255)                                    // fill warm white at half brightness, (r=0–255, g=0–255, b=0–255, w=0–255) → Unit
        Thread.sleep(500)

        strip.brightness = 255                                             // restore full brightness, (value=0–255)

        strip.setPixel(0, 255, 0, 0, 0)                                   // set pixel 0 red (buffer only), (index, r=0–255, g=0–255, b=0–255, w=0–255) → Unit
                                                                           // does not transmit; call show() to push to strip
        strip.setPixel(1, 0, 255, 0, 0)                                   // set pixel 1 green (buffer only), (index, r=0–255, g=0–255, b=0–255, w=0–255) → Unit
        strip.setPixel(2, 0, 0, 255, 0)                                   // set pixel 2 blue (buffer only), (index, r=0–255, g=0–255, b=0–255, w=0–255) → Unit
        strip.setPixel(3, 0, 0, 0, 255)                                   // set pixel 3 white W channel (buffer only), (index, r=0–255, g=0–255, b=0–255, w=0–255) → Unit
        strip.show()                                                       // transmit buffer to strip, () → Unit
                                                                           // sends brightness-scaled GRBW bytes
        Thread.sleep(500)

        strip.setPixels(listOf(intArrayOf(255,0,0,0), intArrayOf(0,255,0,0),
                               intArrayOf(0,0,255,0), intArrayOf(0,0,0,255)))  // set first 4 pixels (buffer only), (colors List<IntArray>) → Unit
                                                                           // 3-element arrays are accepted (w defaults to 0)
        strip.show()                                                       // transmit buffer to strip, () → Unit
        Thread.sleep(500)

        strip.rotate(1)                                                    // shift pixel buffer left 1 position (wraps), (steps=1) → Unit
                                                                           // does not transmit; call show() afterwards
        strip.show()                                                       // transmit rotated buffer, () → Unit
        Thread.sleep(500)

        strip.fillHsv(0.0, 1.0, 1.0)                                     // fill strip with hue=0° (red, w=0) and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Unit
                                                                           // converts HSV to RGB (w=0), then calls fill() which transmits immediately
        Thread.sleep(500)
        strip.fillHsv(0.33, 1.0, 1.0)                                    // fill strip with hue=120° (green, w=0) and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Unit
        Thread.sleep(500)
        strip.fillHsv(0.67, 1.0, 1.0)                                    // fill strip with hue=240° (blue, w=0) and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → Unit
        Thread.sleep(500)

        strip.off()                                                        // turn off all pixels and send, () → Unit
    }
}
