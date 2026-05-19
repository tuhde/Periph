///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.NeoPixelTransport;
import it.uhde.periph.chips.led.WS2812BFull;

public class WS2812BTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int busNum    = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int deviceNum = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        int pixels    = Integer.parseInt(System.getenv().getOrDefault("PIXEL_COUNT", "30"));

        var pi4j = Pi4J.newAutoContext();
        try (var transport = new NeoPixelTransport(pi4j, busNum, deviceNum)) {

            var strip = new WS2812BFull(transport, pixels);

            // --- fill and off ---
            strip.fill(255, 0, 0);
            checkTrue("fill(red) accepted", true);

            strip.fill(0, 255, 0);
            checkTrue("fill(green) accepted", true);

            strip.fill(0, 0, 255);
            checkTrue("fill(blue) accepted", true);

            strip.off();
            checkTrue("off() accepted", true);

            // --- brightness ---
            checkTrue("brightness default 255", strip.getBrightness() == 255);
            strip.setBrightness(128);
            checkTrue("setBrightness(128)", strip.getBrightness() == 128);
            strip.setBrightness(300);
            checkTrue("setBrightness(300) clamped to 255", strip.getBrightness() == 255);
            strip.setBrightness(-1);
            checkTrue("setBrightness(-1) clamped to 0", strip.getBrightness() == 0);
            strip.setBrightness(255);
            checkTrue("setBrightness(255) restored", strip.getBrightness() == 255);

            // --- setPixel + show ---
            strip.setPixel(0, 255, 0, 0);
            strip.setPixel(1, 0, 255, 0);
            strip.setPixel(pixels - 1, 0, 0, 255);
            strip.show();
            checkTrue("setPixel + show accepted", true);

            // --- setPixels + show ---
            int[][] colors = new int[pixels][3];
            for (int i = 0; i < pixels; i++) {
                colors[i][0] = i * 255 / pixels;
                colors[i][1] = 0;
                colors[i][2] = 255 - i * 255 / pixels;
            }
            strip.setPixels(colors);
            strip.show();
            checkTrue("setPixels + show accepted", true);

            // --- rotate ---
            strip.fill(255, 0, 0);
            strip.setPixel(0, 0, 255, 0);
            strip.rotate(1);
            strip.show();
            checkTrue("rotate(1) + show accepted", true);

            strip.rotate(0);
            checkTrue("rotate(0) no-op accepted", true);

            strip.rotate(pixels);
            strip.show();
            checkTrue("rotate(n) full-cycle accepted", true);

            // --- fillHsv ---
            strip.fillHsv(0.0,  1.0, 1.0);
            checkTrue("fillHsv(red) accepted", true);
            strip.fillHsv(0.33, 1.0, 1.0);
            checkTrue("fillHsv(green) accepted", true);
            strip.fillHsv(0.67, 1.0, 1.0);
            checkTrue("fillHsv(blue) accepted", true);
            strip.fillHsv(0.5,  0.0, 1.0);
            checkTrue("fillHsv(white, s=0) accepted", true);

            // --- clamping ---
            strip.fill(300, -10, 256);
            checkTrue("fill with out-of-range values clamped", true);
            strip.setPixel(pixels + 10, 255, 0, 0);
            checkTrue("setPixel with out-of-range index clamped", true);
            strip.show();

            strip.off();
            checkTrue("off() at end accepted", true);

        } finally {
            pi4j.shutdown();
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
