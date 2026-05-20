package it.uhde.periph.chips.led;

/** HSV→RGB conversion shared by all NeoPixel-protocol LED drivers. */
final class NeoPixelColor {

    private NeoPixelColor() {}

    /** Convert HSV (each 0.0–1.0) to [r, g, b] integers 0–255. */
    static int[] hsvToRgb(double h, double s, double v) {
        if (s == 0.0) {
            int c = (int) (v * 255);
            return new int[]{c, c, c};
        }
        int    i = (int) (h * 6.0);
        double f = h * 6.0 - i;
        int    p = (int) (v * (1.0 - s) * 255);
        int    q = (int) (v * (1.0 - s * f) * 255);
        int    t = (int) (v * (1.0 - s * (1.0 - f)) * 255);
        int   vv = (int) (v * 255);
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
