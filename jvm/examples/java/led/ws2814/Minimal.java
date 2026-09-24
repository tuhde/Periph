///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.NeoPixelConnection;
import it.uhde.periph.chips.led.WS2814Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        int pixelCount = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "4"));
        try (var connection = new NeoPixelConnection(spiBus, spiDevice)) {  // open SPI bus, (busNum, deviceNum) → NeoPixelConnection
            var strip = new WS2814Minimal(connection, pixelCount);          // construct driver, (connection, n) → WS2814Minimal

            strip.fill(255, 0, 0, 0);    // fill strip red, (r=0–255, g=0–255, b=0–255, w=0–255) → void
            Thread.sleep(1000);
            strip.fill(0, 255, 0, 0);    // fill strip green, (r=0–255, g=0–255, b=0–255, w=0–255) → void
            Thread.sleep(1000);
            strip.fill(0, 0, 255, 0);    // fill strip blue, (r=0–255, g=0–255, b=0–255, w=0–255) → void
            Thread.sleep(1000);
            strip.fill(0, 0, 0, 255);    // fill strip white (W channel), (r=0–255, g=0–255, b=0–255, w=0–255) → void
            Thread.sleep(1000);
            strip.off();                 // turn off all pixels, () → void
        }
    }
}
