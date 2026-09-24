///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.SiPoConnection;
import it.uhde.periph.chips.io_expander.Tpic6b595Minimal;
import it.uhde.periph.chips.io_expander.Tpic6b595Full;

public class Tpic6b595Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static void checkEq(String label, int got, int expected) {
        if (got == expected) { System.out.println("PASS " + label); passed++; }
        else { System.out.println("FAIL " + label + ": got " + got + ", expected " + expected); failed++; }
    }

    public static void main(String[] args) throws Exception {
        // SiPo hardware: bus 0, device 0, RCK line 17, SRCLR line 16, G line 15.
        try (var connection = SiPoConnection.hardware(0, 0, 17, 16, 15)) {

            var chip = new Tpic6b595Minimal(connection, 1);

            checkEq("init_shadow_0", chip.shadow[0], 0);

            chip.fill(true);
            checkEq("fill_true_shadow", chip.shadow[0], 0xFF);
            chip.fill(false);
            checkEq("fill_false_shadow", chip.shadow[0], 0x00);
            chip.off();
            checkEq("off_shadow", chip.shadow[0], 0);

            chip.writePort(0, 0xA5);
            checkEq("write_port_0xa5_shadow", chip.shadow[0], 0xA5);
            chip.writePort(0, 0);

            var p0 = chip.pin(0);
            p0.setHigh();
            checkEq("pin_on_shadow_bit", chip.shadow[0] & 0x01, 1);
            p0.setLow();
            checkEq("pin_off_shadow_bit", chip.shadow[0] & 0x01, 0);
            p0.toggle();
            checkEq("pin_toggle_shadow_bit", chip.shadow[0] & 0x01, 1);

            p0.setHigh();
            checkEq("pin_set_high_read", p0.read(), true);
            p0.setLow();
            checkEq("pin_set_low_read", p0.read(), false);

            // Cascaded driver
            try (var conn2 = SiPoConnection.hardware(0, 0, 17, 16, 15)) {
                var cascaded = new Tpic6b595Full(conn2, 2);
                checkEq("cascaded_init_shadow_0", cascaded.shadow[0], 0);
                checkEq("cascaded_init_shadow_1", cascaded.shadow[1], 0);
                cascaded.writePort(0, 0x01);
                cascaded.writePort(1, 0x80);
                checkEq("cascaded_write_port_0", cascaded.shadow[0], 0x01);
                checkEq("cascaded_write_port_1", cascaded.shadow[1], 0x80);

                cascaded.clear();
                checkTrue("clear_accepted", true);
                cascaded.setOutputEnable(false);
                checkTrue("set_output_enable_false_accepted", true);
                cascaded.setOutputEnable(true);
                checkTrue("set_output_enable_true_accepted", true);

                int[] bytes = { 0xA5, 0x5A };
                cascaded.writeAll(bytes);
                checkEq("write_all_shadow_0", cascaded.shadow[0], 0xA5);
                checkEq("write_all_shadow_1", cascaded.shadow[1], 0x5A);

                int[] single = { 0xFF };
                cascaded.writeAll(single);
                checkEq("write_all_pad_shadow_0", cascaded.shadow[0], 0xFF);
                checkEq("write_all_pad_shadow_1", cascaded.shadow[1], 0);

                int[] three = { 0x12, 0x34, 0x56 };
                cascaded.writeAll(three);
                checkEq("write_all_truncate_shadow_0", cascaded.shadow[0], 0x12);
                checkEq("write_all_truncate_shadow_1", cascaded.shadow[1], 0x34);
            }
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
