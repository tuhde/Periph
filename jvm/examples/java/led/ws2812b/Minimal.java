///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.NeoPixelConnection;
import it.uhde.periph.chips.led.WS2812BMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        int pixelCount = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "4"));
        try (var connection = new NeoPixelConnection(spiBus, spiDevice)) {  // open SPI bus, (busNum, deviceNum) → NeoPixelConnection
            var strip = new WS2812BMinimal(connection, pixelCount);         // construct driver, (connection, n) → WS2812BMinimal

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
