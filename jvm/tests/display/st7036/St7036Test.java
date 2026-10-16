///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.St7036Full;

// Hardware smoke test: the picture itself is verified by eye (see the demo example).
// Wiring as in the examples: SPI0 CE0, RS=GPIO25, RESET=GPIO17 (no BUSY line).
public class St7036Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int dcGpio   = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_DC", "25"));
        int resGpio  = Integer.parseInt(System.getenv().getOrDefault("DISPLAY_RES", "17"));

        try (var connection = DisplayConnection.hardware(0, 0, 1000000, new SysfsOutputPin(dcGpio),
                new SysfsOutputPin(resGpio), null, WireMode.SIMPLEX)) {
            var lcd = new St7036Full(connection);
            checkTrue("init_valid", true);
            checkTrue("geometry_16x2", lcd.columns() == 16 && lcd.rows() == 2);

            boolean ok;
            try {
                lcd.clear();
                lcd.printAt(0, 0, "Periph ST7036");
                lcd.printAt(0, 1, "smoke test");
                ok = true;
            } catch (java.io.IOException e) { ok = false; }
            checkTrue("text_accepted", ok);
            try {
                lcd.createChar(0, new int[] {0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00});
                lcd.setCursor(15, 1);
                lcd.writeChar(0);
                lcd.cursor(true, true);
                lcd.cursor(false, false);
                lcd.setEntryMode(true, false);
                lcd.shiftDisplay(true);
                lcd.shiftDisplay(false);
                lcd.shiftCursor(true);
                lcd.returnHome();
                lcd.setDdramAddress(0x40);
                lcd.setContrast(40);
                lcd.setBooster(true);
                lcd.setFollower(true, 5);
                lcd.setBias(false);
                lcd.setContrast(24);
                lcd.display(false);
                lcd.display(true);
                lcd.home();
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
