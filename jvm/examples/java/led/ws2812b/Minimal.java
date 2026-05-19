///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.NeoPixelTransport;
import it.uhde.periph.chips.led.WS2812BMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new NeoPixelTransport(0, 0)) {      // open SPI bus 0 device 0, (busNum, deviceNum) → NeoPixelTransport
            var strip = new WS2812BMinimal(transport, 30);              // construct driver, (transport, n=30) → WS2812BMinimal

            strip.fill(255, 0, 0);    // fill strip red, (r=0–255, g=0–255, b=0–255) → void
            Thread.sleep(1000);
            strip.fill(0, 255, 0);    // fill strip green, (r=0–255, g=0–255, b=0–255) → void
            Thread.sleep(1000);
            strip.fill(0, 0, 255);    // fill strip blue, (r=0–255, g=0–255, b=0–255) → void
            Thread.sleep(1000);
            strip.off();              // turn off all pixels, () → void
        }
    }
}
