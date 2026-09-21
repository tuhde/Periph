///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.led.APA102Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        int pixelCount = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "30"));
        try (var connection = new SPIConnection(spiBus, spiDevice, 0, 1_000_000)) { // open SPI bus, (busNum, deviceNum, mode, speedHz) → SPIConnection
            var strip = new APA102Minimal(connection, pixelCount);                  // construct driver, (connection, n) → APA102Minimal

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