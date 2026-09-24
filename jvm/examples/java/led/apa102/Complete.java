///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.led.APA102Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        int pixelCount = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "8"));
        try (var connection = new SPIConnection(spiBus, spiDevice, 0, 1_000_000)) { // open SPI bus, (busNum, deviceNum, mode, speedHz) → SPIConnection
            var strip = new APA102Full(connection, pixelCount);                     // construct driver, (connection, n) → APA102Full

            // fill — set all pixels and send immediately
            strip.fill(255, 0, 0);                                    // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                                      // stores brightness/B/G/R in buffer and calls connection.write()
            Thread.sleep(500);

            // set individual pixels then show
            strip.setPixel(0, 255, 0, 0, 31);                         // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
            strip.setPixel(1, 0, 255, 0, 31);                         // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
            strip.setPixel(2, 0, 0, 255, 31);                         // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                      // writes brightness, B, G, R bytes into internal buffer at position index*4
            strip.show();                                             // Transmit buffer to strip, () → void
                                                                      // applies software brightness scaling then calls connection.write()
            Thread.sleep(500);

            // set_pixels — write multiple pixels at once
            strip.setPixels(new int[][]{                               // Set pixels from array of [r,g,b] or [r,g,b,brightness], (colors=int[][]) → void
                {255, 128, 0},   {128, 0, 255},   {0, 255, 128},
                {255, 255, 0},   {0, 255, 255},   {255, 0, 255},
                {128, 128, 128}, {255, 255, 255}
            });                                                       // writes entries sequentially from pixel 0; ignores extras beyond strip length
            strip.show();                                             // Transmit buffer to strip, () → void
                                                                      // applies software brightness scaling then calls connection.write()
            Thread.sleep(500);

            // set_pixels with per-pixel hardware brightness
            strip.setPixels(new int[][]{                               // Set pixels with varying hardware brightness, (colors=int[][]) → void
                {255, 0, 0, 31}, {255, 0, 0, 16}, {255, 0, 0, 8},  {255, 0, 0, 4},
                {0, 255, 0, 31}, {0, 255, 0, 16}, {0, 255, 0, 8},  {0, 255, 0, 4}
            });                                                       // writes entries sequentially from pixel 0; ignores extras beyond strip length
            strip.show();                                             // Transmit buffer to strip, () → void
                                                                      // applies software brightness scaling then calls connection.write()
            Thread.sleep(500);

            // brightness — global software scale applied at show() time
            strip.setBrightness(64);                                  // Set global software brightness, (value=0–255) → void
                                                                      // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
            strip.show();                                             // Transmit buffer to strip, () → void
                                                                      // applies software brightness scaling then calls connection.write()
            Thread.sleep(500);
            strip.setBrightness(255);                                 // Set global software brightness, (value=0–255) → void
                                                                      // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

            // fill_hsv — fill all pixels from HSV colour
            strip.fillHsv(0.0, 1.0, 1.0);                             // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                      // converts HSV to RGB then calls fill(); hue 0.0 = red
            Thread.sleep(500);
            strip.fillHsv(0.333, 1.0, 1.0);                           // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                      // converts HSV to RGB then calls fill(); hue 0.333 = green
            Thread.sleep(500);
            strip.fillHsv(0.667, 1.0, 1.0);                           // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                      // converts HSV to RGB then calls fill(); hue 0.667 = blue
            Thread.sleep(500);

            // rotate — shift pixel buffer left, then show
            strip.setPixels(new int[][]{                               // Set pixels from array of [r,g,b], (colors=int[][]) → void
                {255, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
                {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}
            });                                                       // writes entries sequentially from pixel 0; ignores extras beyond strip length
            strip.show();                                             // Transmit buffer to strip, () → void
                                                                      // applies software brightness scaling then calls connection.write()
            Thread.sleep(500);
            for (int i = 0; i < 7; i++) {
                strip.rotate(1);                                      // Rotate pixel buffer left, (steps=1) → void
                                                                      // shifts buffer by steps pixel positions; wraps around; does not send
                strip.show();                                         // Transmit buffer to strip, () → void
                                                                      // applies software brightness scaling then calls connection.write()
                Thread.sleep(200);
            }

            strip.off();                                              // Turn off all pixels, () → void
                                                                      // equivalent to fill(0, 0, 0)
            Thread.sleep(1000);
        }
    }
}