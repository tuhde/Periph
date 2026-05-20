///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.NeoPixelTransport;
import it.uhde.periph.chips.led.SK6812RGBWFull;

/**
 * Rainbow rotation + warm-white strobe demo.
 *
 * Runs a rotating rainbow (RGB, w=0) for 10 seconds at ~30 fps, then strobes
 * warm white (r=255, g=200, b=150, w=255) at 5 Hz for 2 seconds to showcase
 * the dedicated white element, then returns to the rainbow.
 */
public class Demo {

    private static final long FRAME_MS  = 33;
    private static final int  RAINBOW_S = 10;
    private static final int  WARM_S    = 2;
    private static final long WARM_HALF = 100;

    public static void main(String[] args) throws Exception {
        int spiBus     = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",     "0"));
        int spiDevice  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE",  "0"));
        int PIXELS     = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "4"));
        try (var transport = new NeoPixelTransport(spiBus, spiDevice)) {  // open SPI bus, (busNum, deviceNum) → NeoPixelTransport
            var strip = new SK6812RGBWFull(transport, PIXELS);             // construct driver, (transport, n) → SK6812RGBWFull

            // --- Rainbow rotation for 10 seconds ---
            // Each pixel gets a hue offset by (pixel_index / n_pixels) of the colour
            // wheel, and the hue offset advances each frame so the rainbow rotates.
            // RGB channels only (w=0); the dedicated white element stays off.
            strip.setBrightness(200);                                    // set brightness to ~78%, (value=0–255) → void
            double hueOffset = 0.0;
            long rainbowEnd = System.currentTimeMillis() + RAINBOW_S * 1000L;
            long lastPrint  = System.currentTimeMillis();

            while (System.currentTimeMillis() < rainbowEnd) {
                int[][] colors = new int[PIXELS][4];
                for (int p = 0; p < PIXELS; p++) {
                    double hue = (hueOffset + (double) p / PIXELS) % 1.0;
                    int[] rgb = hsvToRgb(hue, 1.0, 1.0);
                    colors[p] = new int[]{rgb[0], rgb[1], rgb[2], 0};
                }
                strip.setPixels(colors);                                  // load rainbow frame into buffer (w=0), (colors[][r,g,b,w]) → void
                strip.show();                                             // transmit frame to strip, () → void

                hueOffset = (hueOffset + 1.0 / 300) % 1.0;
                if (System.currentTimeMillis() - lastPrint >= 1000) {
                    System.out.printf("hue offset: %.3f%n", hueOffset);
                    lastPrint = System.currentTimeMillis();
                }
                Thread.sleep(FRAME_MS);
            }

            // --- Warm-white strobe: showcases the dedicated white element.
            //     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
            //     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
            //     difference between mixed-RGB white and the native W element. ---
            strip.setBrightness(255);                                    // restore full brightness, (value=0–255) → void
            long warmEnd = System.currentTimeMillis() + WARM_S * 1000L;

            while (System.currentTimeMillis() < warmEnd) {
                strip.fill(255, 200, 150, 255);                          // flash warm white (RGB+W), (r=0–255, g=0–255, b=0–255, w=0–255) → void
                Thread.sleep(WARM_HALF);
                strip.off();                                             // flash off, () → void
                Thread.sleep(WARM_HALF);
            }

            // --- Return to rainbow ---
            strip.setBrightness(200);                                    // dim for second rainbow phase, (value=0–255) → void
            long resumeEnd = System.currentTimeMillis() + RAINBOW_S * 1000L;

            while (System.currentTimeMillis() < resumeEnd) {
                int[][] colors = new int[PIXELS][4];
                for (int p = 0; p < PIXELS; p++) {
                    double hue = (hueOffset + (double) p / PIXELS) % 1.0;
                    int[] rgb = hsvToRgb(hue, 1.0, 1.0);
                    colors[p] = new int[]{rgb[0], rgb[1], rgb[2], 0};
                }
                strip.setPixels(colors);                                  // load rainbow frame into buffer (w=0), (colors[][r,g,b,w]) → void
                strip.show();                                             // transmit frame to strip, () → void
                hueOffset = (hueOffset + 1.0 / 300) % 1.0;
                Thread.sleep(FRAME_MS);
            }

            strip.off();                                                  // turn off strip at exit, () → void
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
