///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.NeoPixelConnection;
import it.uhde.periph.chips.led.WS2814Full;

/**
 * Rainbow rotation + warm/cool white cycle demo.
 *
 * Begins with 5 seconds of rainbow rotation (RGB channels, w=0 per pixel)
 * at ~30 fps, then flashes warm white (r=255, g=200, b=150, w=255) at full
 * brightness for 2 seconds, then dims the warm white to 50% using the
 * brightness property and holds for 2 seconds, then cycles to cool white
 * (r=200, g=210, b=255, w=255). Prints the current mode and brightness
 * once per second. This exercises the white channel, brightness property,
 * per-pixel RGBW addressing, and HSV convenience method, and demonstrates
 * that the WS2814's RGBW order requires no reorder compared to the
 * SK6812RGBW.
 */
public class Demo {

    private static final long FRAME_MS    = 33;
    private static final int  RAINBOW_S   = 5;
    private static final int  FLASH_S     = 2;
    private static final int  DIM_S       = 2;

    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        int PIXELS     = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "30"));
        try (var connection = new NeoPixelConnection(spiBus, spiDevice)) {  // open SPI bus, (busNum, deviceNum) → NeoPixelConnection
            var strip = new WS2814Full(connection, PIXELS);                 // construct driver, (connection, n) → WS2814Full

            // --- Rainbow rotation using RGB channels (white=0). Each pixel is
            //     assigned a hue offset by its position; the offset advances each
            //     frame so the rainbow rotates continuously around the strip.
            //     Demonstrates that WS2814's RGBW wire order is identity (R, G, B, W
            //     with no reorder), unlike the SK6812RGBW's GRBW order. Runs at
            //     ~30 fps for 5 seconds. ---
            double hueOffset = 0.0;
            long rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L;
            long lastPrint = System.currentTimeMillis();

            while (System.currentTimeMillis() < rainbowEnd) {
                int[][] colors = new int[PIXELS][4];
                for (int p = 0; p < PIXELS; p++) {
                    double hue = (hueOffset + (double) p / PIXELS) % 1.0;
                    int[] rgb = hsvToRgb(hue, 1.0, 1.0);
                    colors[p] = new int[]{rgb[0], rgb[1], rgb[2], 0};
                }
                strip.setPixels(colors);                                  // load rainbow frame into buffer (w=0), (colors[][r,g,b,w]) → void
                strip.show();                                             // transmit frame to strip, () → void

                hueOffset = (hueOffset + 1.0 / (PIXELS * 2)) % 1.0;
                long now = System.currentTimeMillis();
                if (now - lastPrint >= 1000) {
                    System.out.printf("mode=rainbow brightness=%d%n", strip.getBrightness());
                    lastPrint = now;
                }
                Thread.sleep(FRAME_MS);
            }

            // --- Warm white at full brightness for 2 seconds. r=255, g=200, b=150,
            //     w=255 blends the dedicated white element with amber-tinted RGB,
            //     exercising the white channel and the 32-bit RGBW pixel word at
            //     full brightness. ---
            strip.fill(255, 200, 150, 255);                              // fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
            long flashEnd = System.currentTimeMillis() + FLASH_S * 1000L;
            while (System.currentTimeMillis() < flashEnd) {
                System.out.printf("mode=warm-white brightness=%d%n", strip.getBrightness());
                Thread.sleep(100);
            }

            // --- Dim warm white to 50% using the brightness property and hold for
            //     2 seconds. Demonstrates that brightness scaling is non-destructive:
            //     the stored RGBW values are unchanged, only the scale factor applied
            //     at show() time changes. ---
            strip.setBrightness(128);                                    // set global brightness, (value=0–255) → void
            strip.show();                                                // transmit buffer to strip, () → void
            long dimEnd = System.currentTimeMillis() + DIM_S * 1000L;
            while (System.currentTimeMillis() < dimEnd) {
                System.out.printf("mode=warm-white-dimmed brightness=%d%n", strip.getBrightness());
                Thread.sleep(100);
            }

            // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
            //     w=255 shifts the blend toward blue, showcasing the dedicated
            //     white element paired with a cool-tinted RGB base. ---
            strip.setBrightness(255);                                    // set global brightness, (value=0–255) → void
            strip.fill(200, 210, 255, 255);                              // fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
            System.out.printf("mode=cool-white brightness=%d%n", strip.getBrightness());
        }
    }

    private static int[] hsvToRgb(double h, double s, double v) {
        if (s == 0.0) { int c = (int)(v*255); return new int[]{c,c,c}; }
        int i    = (int)(h * 6.0);
        double f = h * 6.0 - i;
        int p    = (int)(v * (1.0 - s) * 255);
        int q    = (int)(v * (1.0 - s * f) * 255);
        int t    = (int)(v * (1.0 - s * (1.0 - f)) * 255);
        int vv   = (int)(v * 255);
        switch (i % 6) {
            case 0: return new int[]{vv, t, p};
            case 1: return new int[]{q, vv, p};
            case 2: return new int[]{p, vv, t};
            case 3: return new int[]{p, q, vv};
            case 4: return new int[]{t, p, vv};
            default: return new int[]{vv, p, q};
        }
    }
}
