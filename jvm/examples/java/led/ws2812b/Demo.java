///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.NeoPixelTransport;
import it.uhde.periph.chips.led.WS2812BFull;

/**
 * Rainbow rotation + strobe demo.
 *
 * Runs a rotating rainbow across the strip for 10 seconds at ~30 fps, then
 * strobes full white / off at 10 Hz for 2 seconds, then returns to the rainbow.
 * Exercises brightness, per-pixel addressing, and timing.
 */
public class Demo {

    private static final int    PIXELS    = 30;
    private static final long   FRAME_MS  = 33;   // ~30 fps
    private static final int    RAINBOW_S = 10;
    private static final int    STROBE_S  = 2;
    private static final int    STROBE_HZ = 10;

    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                                // initialise Pi4J, () → Context
        try (var transport = new NeoPixelTransport(pi4j, 0, 0)) {      // open SPI bus 0 device 0, (busNum, deviceNum) → NeoPixelTransport
            var strip = new WS2812BFull(transport, PIXELS);             // construct driver, (transport, n=30) → WS2812BFull

            // --- Rainbow rotation for 10 seconds ---
            // Each pixel gets a hue offset by (pixel_index / n_pixels) of the colour
            // wheel, and the hue offset advances by 1/300 each frame so the rainbow
            // rotates around the strip at about 1 revolution per second.
            strip.setBrightness(200);                                    // set brightness to ~78%, (value=0–255) → void
            double hueOffset = 0.0;
            long rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L;
            long lastPrint  = System.currentTimeMillis();

            while (System.currentTimeMillis() < rainbowEnd) {
                for (int p = 0; p < PIXELS; p++) {
                    double hue = (hueOffset + (double) p / PIXELS) % 1.0;
                    strip.setPixel(p, 0, 0, 0);                         // clear pixel (buffer only), (index, r=0, g=0, b=0) → void
                    // Inline HSV→RGB for the strip: set via setPixels below
                }
                // Build a colour array for all pixels and push in one show() call
                int[][] colors = new int[PIXELS][3];
                for (int p = 0; p < PIXELS; p++) {
                    double hue = (hueOffset + (double) p / PIXELS) % 1.0;
                    colors[p] = hsvToRgb(hue, 1.0, 1.0);
                }
                strip.setPixels(colors);                                  // load rainbow frame into buffer, (colors[][r,g,b]) → void
                strip.show();                                             // transmit frame to strip, () → void

                hueOffset = (hueOffset + 1.0 / 300) % 1.0;

                if (System.currentTimeMillis() - lastPrint >= 1000) {
                    System.out.printf("hue offset: %.3f%n", hueOffset);
                    lastPrint = System.currentTimeMillis();
                }
                Thread.sleep(FRAME_MS);
            }

            // --- Strobe: full white / off at 10 Hz for 2 seconds ---
            // A fast on/off flash tests the brightness property and demonstrates
            // fill() as an immediate update path distinct from set_pixel + show().
            strip.setBrightness(255);                                    // restore full brightness for strobe, (value=0–255) → void
            long strobeEnd    = System.currentTimeMillis() + STROBE_S * 1000L;
            long halfPeriodMs = 1000L / (STROBE_HZ * 2);

            while (System.currentTimeMillis() < strobeEnd) {
                strip.fill(255, 255, 255);                               // flash white, (r=0–255, g=0–255, b=0–255) → void
                Thread.sleep(halfPeriodMs);
                strip.off();                                             // flash off, () → void
                Thread.sleep(halfPeriodMs);
            }

            // --- Return to rainbow ---
            // Resume from the hue offset where the first phase stopped.
            strip.setBrightness(200);                                    // dim for second rainbow phase, (value=0–255) → void
            long resumeEnd = System.currentTimeMillis() + RAINBOW_S * 1000L;

            while (System.currentTimeMillis() < resumeEnd) {
                int[][] colors = new int[PIXELS][3];
                for (int p = 0; p < PIXELS; p++) {
                    double hue = (hueOffset + (double) p / PIXELS) % 1.0;
                    colors[p] = hsvToRgb(hue, 1.0, 1.0);
                }
                strip.setPixels(colors);                                  // load rainbow frame into buffer, (colors[][r,g,b]) → void
                strip.show();                                             // transmit frame to strip, () → void
                hueOffset = (hueOffset + 1.0 / 300) % 1.0;
                Thread.sleep(FRAME_MS);
            }

            strip.off();                                                  // turn off strip at exit, () → void

        } finally {
            pi4j.shutdown();
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
