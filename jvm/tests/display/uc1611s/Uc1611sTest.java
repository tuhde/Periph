///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.Uc1611sFull;

public class Uc1611sTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));

        try (var command = new I2CConnection(bus, addr);
             var data = new I2CConnection(bus, addr + 1)) {
            var chip = new Uc1611sFull(command, data);
            checkTrue("geometry_240x128", chip.width() == 240 && chip.height() == 128);

            byte[] s = chip.readStatus();
            checkTrue("status_version_bit", (s[0] & 0x80) != 0);
            checkTrue("status_product_code_1", ((s[2] & 0xFF) >> 4) == 1);
            checkTrue("status_display_enabled", (s[0] & 0x08) != 0);
            checkTrue("status_mirror_x_bottom_view", (s[0] & 0x40) != 0 && (s[0] & 0x20) == 0);

            chip.sleep();
            checkTrue("sleep_clears_display_enable", (chip.readStatus()[0] & 0x08) == 0);
            chip.wake();
            checkTrue("wake_sets_display_enable", (chip.readStatus()[0] & 0x08) != 0);

            chip.setFlip(false, true);
            s = chip.readStatus();
            checkTrue("flip_my_status", (s[0] & 0x40) == 0 && (s[0] & 0x20) != 0);
            chip.setFlip(true, false);
            s = chip.readStatus();
            checkTrue("flip_mx_status", (s[0] & 0x40) != 0 && (s[0] & 0x20) == 0);

            // RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
            chip.fill(false);
            chip.drawLine(0, 0, 31, 7, true);
            chip.drawLine(0, 31, 31, 24, true);
            chip.drawRect(40, 4, 20, 20, true, false);
            chip.show();
            boolean match0 = true, match3 = true;
            for (int half = 0; half < 2; half++) {
                byte[] ram = chip.readRam(0, half * 32, 32);
                for (int x = 0; x < 32; x++) {
                    int expected = 0;
                    for (int b = 0; b < 8; b++) if (chip.getPixel(half * 32 + x, b)) expected |= 1 << b;
                    if ((ram[x] & 0xFF) != expected) match0 = false;
                }
                ram = chip.readRam(3, half * 32, 32);
                for (int x = 0; x < 32; x++) {
                    int expected = 0;
                    for (int b = 0; b < 8; b++) if (chip.getPixel(half * 32 + x, 24 + b)) expected |= 1 << b;
                    if ((ram[x] & 0xFF) != expected) match3 = false;
                }
            }
            checkTrue("ram_readback_page0", match0);
            checkTrue("ram_readback_page3", match3);

            chip.setContrast(0x70);
            chip.setContrast(0x8F);
            chip.setInvert(true);
            chip.setInvert(false);
            chip.setAllOn(true);
            chip.setAllOn(false);
            chip.setScrollLine(16);
            chip.setScrollLine(0);
            chip.setFixedLines(0);
            chip.setBiasRatio(2);
            chip.setTemperatureCompensation(1);
            chip.setLineRate(3);
            chip.setPump(true);
            chip.setPanelLoading(true);
            chip.setPartialDisplay(true, 0, 127);
            chip.setPartialDisplay(false, 0, -1);
            chip.setNLineInversion(0, false);
            chip.setIsolationClock(1, 0);
            chip.setWritePattern(1);
            chip.showPage(0);
            chip.setWritePattern(0);
            chip.setDisplayPattern(1);
            chip.setDisplayPattern(0);
            chip.show();
            checkTrue("commands_accepted", true);

            chip.reset();
            checkTrue("reset_restores_display_enable", (chip.readStatus()[0] & 0x08) != 0);
            System.out.println("INFO driver retries " + chip.retryCount() + ", unrecovered " + chip.errorCount());
            checkTrue("no_unrecovered_transfer_errors", chip.ok());
        } catch (Exception e) {
            System.out.println("FAIL exception: " + e);
            failed++;
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
