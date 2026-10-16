///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.Ssd1331Full;

// Hardware smoke test: the picture itself is verified by eye (see the demo example).
// Wiring as in the examples: SPI0 CE0, DC=GPIO25, RST=GPIO17 (no BUSY line).
public class Ssd1331Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int dcGpio   = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_DC", "25"));
        int resGpio  = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_RES", "17"));

        try (var connection = DisplayConnection.hardware(0, 0, 4000000, new SysfsOutputPin(dcGpio),
                new SysfsOutputPin(resGpio), null, WireMode.SIMPLEX)) {
            var epd = new Ssd1331Full(connection);
            checkTrue("init_valid", true);

            epd.clear(Ssd1331Full.BLUE);
            checkTrue("clear_readback", epd.getPixel(0, 0) == Ssd1331Full.BLUE && epd.getPixel(95, 63) == Ssd1331Full.BLUE);
            epd.setPixel(5, 6, Ssd1331Full.RED);
            checkTrue("pixel_readback", epd.getPixel(5, 6) == Ssd1331Full.RED);
            epd.drawRect(10, 10, 20, 10, Ssd1331Full.GREEN, true);
            checkTrue("draw_rect_fill", epd.getPixel(15, 15) == Ssd1331Full.GREEN);
            epd.drawLine(0, 0, 95, 63, Ssd1331Full.WHITE);
            checkTrue("draw_line_endpoints", epd.getPixel(0, 0) == Ssd1331Full.WHITE && epd.getPixel(95, 63) == Ssd1331Full.WHITE);

            boolean ok;
            try {
                epd.show();
                epd.showRegion(8, 8, 32, 16);
                epd.hwClear(0, 0, 95, 63);
                epd.hwRect(10, 10, 40, 30, Ssd1331Full.YELLOW, Ssd1331Full.RED, true);
                epd.hwLine(0, 0, 95, 63, Ssd1331Full.WHITE);
                epd.hwCopy(10, 10, 30, 30, 50, 20);
                epd.hwDim(0, 0, 47, 63);
                epd.setContrast(0x80, 0x80, 0x80);
                epd.setMasterCurrent(7);
                epd.setDisplayMode(Ssd1331Full.MODE_INVERSE);
                epd.setDisplayMode(Ssd1331Full.MODE_NORMAL);
                epd.setFlip(false, false);
                epd.scroll(1, 0, 64, 0, 0);
                epd.scrollStop();
                epd.displayOff();
                epd.displayOn();
                ok = true;
            } catch (java.io.IOException e) { ok = false; }
            checkTrue("commands_accepted", ok);
        } catch (java.io.IOException e) {
            checkTrue("init_valid", false);
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
