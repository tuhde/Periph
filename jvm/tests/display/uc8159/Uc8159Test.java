///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.PollingInputPin;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.Uc8159Full;

// Hardware smoke test: needs a panel, show() waits for the real refresh.
// Wiring as in the examples: SPI0 CE0, DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
public class Uc8159Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int dcGpio   = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_DC", "25"));
        int resGpio  = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_RES", "17"));
        int busyLine = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_BUSY", "24"));

        try (var connection = DisplayConnection.hardware(0, 0, 4000000, new SysfsOutputPin(dcGpio),
                new SysfsOutputPin(resGpio), new PollingInputPin("/dev/gpiochip0", busyLine), WireMode.SIMPLEX)) {
            var epd = new Uc8159Full(connection);
            checkTrue("init_valid", true);

            epd.clear(Uc8159Full.WHITE);
            epd.setPixel(1, 1, Uc8159Full.BLACK);
            checkTrue("pixel_black_readback", epd.getPixel(1, 1) == Uc8159Full.BLACK);
            epd.setPixel(3, 1, Uc8159Full.RED);
            checkTrue("pixel_red_readback", epd.getPixel(3, 1) == Uc8159Full.RED);
            epd.setPixel(4, 1, Uc8159Full.ORANGE);
            checkTrue("pixel_orange_readback", epd.getPixel(4, 1) == Uc8159Full.ORANGE);
            epd.drawRect(0, 0, 600, 448, Uc8159Full.BLACK, false);
            epd.drawLine(0, 0, 599, 447, Uc8159Full.BLACK);
            checkTrue("drawing_accepted", epd.getPixel(0, 0) == Uc8159Full.BLACK);

            boolean shown;
            try { epd.show(); shown = true; } catch (java.io.IOException e) { shown = false; }
            checkTrue("show_completes", shown);
            epd.sleep();
            checkTrue("sleep_accepted", true);
        } catch (java.io.IOException e) {
            checkTrue("init_valid", false);
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
