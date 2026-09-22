///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.led.APA102Full;

public class Demo {
    private static final int N_PIXELS = 30;
    private static final int FRAME_MS = 16;      // ~60 fps
    private static final int RAINBOW_MS = 10000;

    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        try (var connection = new SPIConnection(spiBus, spiDevice, 0, 1_000_000)) { // open SPI bus, (busNum, deviceNum, mode, speedHz) → SPIConnection
            var strip = new APA102Full(connection, N_PIXELS);                       // construct driver, (connection, n) → APA102Full

            // --- 13-bit effective color depth demonstration ---
            // First pass: full hardware brightness (31) for maximum drive current
            // Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

            // --- Pass 1: Full hardware brightness (31) ---
            // Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
            // hardware current control = 13-bit effective depth per channel.
            strip.setBrightness(255);                                              // Set global software brightness, (value=0–255) → void
            double hueOffset = 0.0;
            long start = System.currentTimeMillis();
            long lastPrint = start;
            while (System.currentTimeMillis() - start < RAINBOW_MS) {
                for (int i = 0; i < N_PIXELS; i++) {
                    double h = (hueOffset + (double) i / N_PIXELS) % 1.0;
                    int[] rgb = hsvToRgb(h, 1.0, 1.0);
                    strip.setPixel(i, rgb[0], rgb[1], rgb[2], 31);                  // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                }
                strip.show();                                                       // Transmit buffer to strip, () → void
                                                                                     // applies software brightness scaling then calls connection.write()
                hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2.0)) % 1.0;
                long now = System.currentTimeMillis();
                if (now - lastPrint >= 1000) {
                    System.out.printf("rainbow hw_brightness=31 hue_offset=%.3f%n", hueOffset);
                    lastPrint = now;
                }
                long elapsed = System.currentTimeMillis() - now;
                if (elapsed < FRAME_MS) Thread.sleep(FRAME_MS - elapsed);
            }

            // --- Pass 2: Low hardware brightness (1) ---
            // Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
            // Demonstrates hardware current control vs software brightness scaling.
            strip.setBrightness(255);                                              // Set global software brightness, (value=0–255) → void
            hueOffset = 0.0;
            start = System.currentTimeMillis();
            lastPrint = start;
            while (System.currentTimeMillis() - start < RAINBOW_MS) {
                for (int i = 0; i < N_PIXELS; i++) {
                    double h = (hueOffset + (double) i / N_PIXELS) % 1.0;
                    int[] rgb = hsvToRgb(h, 1.0, 1.0);
                    strip.setPixel(i, rgb[0], rgb[1], rgb[2], 1);                   // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                }
                strip.show();                                                       // Transmit buffer to strip, () → void
                                                                                     // applies software brightness scaling then calls connection.write()
                hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2.0)) % 1.0;
                long now = System.currentTimeMillis();
                if (now - lastPrint >= 1000) {
                    System.out.printf("rainbow hw_brightness=1 hue_offset=%.3f%n", hueOffset);
                    lastPrint = now;
                }
                long elapsed = System.currentTimeMillis() - now;
                if (elapsed < FRAME_MS) Thread.sleep(FRAME_MS - elapsed);
            }

            strip.off();                                                            // Turn off all pixels, () → void
            Thread.sleep(1000);
        }
    }

    private static int[] hsvToRgb(double h, double s, double v) {
        if (s == 0.0) {
            int c = (int) (v * 255);
            return new int[]{c, c, c};
        }
        int i = (int) (h * 6.0);
        double f = h * 6.0 - i;
        int p = (int) (v * (1.0 - s) * 255);
        int q = (int) (v * (1.0 - s * f) * 255);
        int t = (int) (v * (1.0 - s * (1.0 - f)) * 255);
        int vv = (int) (v * 255);
        return switch (i % 6) {
            case 0 -> new int[]{vv, t, p};
            case 1 -> new int[]{q, vv, p};
            case 2 -> new int[]{p, vv, t};
            case 3 -> new int[]{p, q, vv};
            case 4 -> new int[]{t, p, vv};
            default -> new int[]{vv, p, q};
        };
    }
}