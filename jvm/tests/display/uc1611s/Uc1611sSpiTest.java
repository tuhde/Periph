///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.Uc1611sSpiFull;

// Hardware smoke test of the 4-wire SPI driver: the module is write-only, so the picture itself is verified by eye
// (see the demo example). Wiring as in the examples: SPI0 CE0, CD=GPIO25, RST=GPIO17 (no BUSY line).
public class Uc1611sSpiTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int dcGpio  = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_DC", "25"));
        int resGpio = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_RES", "17"));

        try (var connection = DisplayConnection.hardware(0, 0, 4000000, new SysfsOutputPin(dcGpio),
                new SysfsOutputPin(resGpio), null, WireMode.SIMPLEX)) {
            var lcd = new Uc1611sSpiFull(connection);
            checkTrue("init_valid", true);
            checkTrue("geometry_240x128", lcd.width() == 240 && lcd.height() == 128);

            boolean ok;
            try {
                lcd.clear();
                lcd.drawRect(0, 0, 240, 128, true, false);
                lcd.drawLine(0, 0, 239, 127, true);
                lcd.drawRect(20, 20, 40, 30, true, true);
                lcd.show();
                ok = lcd.ok();
            } catch (java.io.IOException e) { ok = false; }
            checkTrue("show_accepted", ok);
            try {
                lcd.showPage(3);
                lcd.setContrast(0x70);
                lcd.setContrast(0x8F);
                lcd.setInvert(true);
                lcd.setInvert(false);
                lcd.setAllOn(true);
                lcd.setAllOn(false);
                lcd.setFlip(false, true);
                lcd.setFlip(true, false);
                lcd.setFixedLines(2);
                lcd.setScrollLine(8);
                lcd.setScrollLine(0);
                lcd.setFixedLines(0);
                lcd.setBiasRatio(2);
                lcd.setTemperatureCompensation(1);
                lcd.setLineRate(3);
                lcd.setPump(true);
                lcd.setPanelLoading(true);
                lcd.setNLineInversion(0, false);
                lcd.setIsolationClock(1, 0);
                lcd.setPartialDisplay(true, 32, 95);
                lcd.setPartialDisplay(false, 0, -1);
                lcd.setWritePattern(1);
                lcd.show();
                lcd.setDisplayPattern(1);
                lcd.setDisplayPattern(0);
                lcd.setWritePattern(0);
                ok = lcd.ok();
            } catch (java.io.IOException e) { ok = false; }
            checkTrue("commands_accepted", ok);
            try {
                lcd.sleep();
                lcd.wake();
                lcd.reset();
                lcd.show();
                ok = lcd.ok();
            } catch (java.io.IOException e) { ok = false; }
            checkTrue("sleep_wake_reset_accepted", ok);

            boolean threw = false;
            try { lcd.readStatus(); } catch (UnsupportedOperationException e) { threw = true; }
            checkTrue("read_status_unsupported", threw);
            threw = false;
            try { lcd.readRam(0, 0, 4); } catch (UnsupportedOperationException e) { threw = true; }
            checkTrue("read_ram_unsupported", threw);
            checkTrue("no_retries_or_errors", lcd.retryCount() == 0 && lcd.errorCount() == 0);
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
