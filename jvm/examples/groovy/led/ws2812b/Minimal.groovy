///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.NeoPixelTransport
import it.uhde.periph.chips.led.WS2812BMinimal

def pi4j = Pi4J.newAutoContext()                                    // initialise Pi4J, () → Context
try {
    def transport = new NeoPixelTransport(pi4j, 0, 0)              // open SPI bus 0 device 0, (busNum, deviceNum) → NeoPixelTransport
    try {
        def strip = new WS2812BMinimal(transport, 30)               // construct driver, (transport, n=30) → WS2812BMinimal

        strip.fill(255, 0, 0)    // fill strip red, (r=0–255, g=0–255, b=0–255) → void
        Thread.sleep(1000)
        strip.fill(0, 255, 0)    // fill strip green, (r=0–255, g=0–255, b=0–255) → void
        Thread.sleep(1000)
        strip.fill(0, 0, 255)    // fill strip blue, (r=0–255, g=0–255, b=0–255) → void
        Thread.sleep(1000)
        strip.off()              // turn off all pixels, () → void
    } finally {
        transport.close()
    }
} finally {
    pi4j.shutdown()
}
