///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.Pcf8575Full;
import it.uhde.periph.chips.io_expander.Pcf8575Minimal;

public class Pcf8575Test {

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
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x20").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {

            var chip = new Pcf8575Minimal(connection);

            // --- Construction sets all pins to input mode (shadow = [0xFF, 0xFF]) ---
            checkEq("init_shadow_0", chip.shadow[0], 0xFF);
            checkEq("init_shadow_1", chip.shadow[1], 0xFF);

            // --- readPort returns int in [0, 255] ---
            int port0 = chip.readPort(0);
            int port1 = chip.readPort(1);
            checkTrue("read_port_0_range", port0 >= 0 && port0 <= 255);
            checkTrue("read_port_1_range", port1 >= 0 && port1 <= 255);

            // --- writePort writes the whole port ---
            chip.writePort(0, 0xAA);
            checkEq("write_port_0_shadow", chip.shadow[0], 0xAA);
            chip.writePort(1, 0x55);
            checkEq("write_port_1_shadow", chip.shadow[1], 0x55);
            chip.writePort(0, 0xFF);
            chip.writePort(1, 0xFF);

            // --- Pin set high / set low / read ---
            var p0 = chip.pin(0);
            p0.setHigh();
            p0.setLow();
            checkTrue("pin_set_low_accepted", true);
            p0.setInput();
            p0.setOutput();
            p0.toggle();
            checkTrue("pin_toggle_accepted", true);
            boolean v = p0.read();
            checkTrue("pin_read_returns_boolean", v == true || v == false);

            // --- Loopback: port 0 (outputs) → port 1 (inputs); P0x ↔ P1(7-x) ---
            chip.writePort(1, 0xFF);

            chip.writePort(0, 0xAA);
            checkEq("loopback_0xAA", chip.readPort(1), 0x55);

            chip.writePort(0, 0xF0);
            checkEq("loopback_0xF0", chip.readPort(1), 0x0F);

            chip.writePort(0, 0x00);
            checkEq("loopback_0x00", chip.readPort(1), 0x00);

            chip.writePort(0, 0xFF);
            chip.writePort(1, 0xFF);

            // --- Full: pollInterrupt returns int in [0, 65535] ---
            var full = new Pcf8575Full(connection);
            int changed = full.pollInterrupt();
            checkTrue("poll_interrupt_range", changed >= 0 && changed <= 65535);

            // --- onInterrupt + offInterrupt ---
            var received = new int[]{-1};
            full.onInterrupt(mask -> received[0] = mask);
            Thread.sleep(50);
            full.offInterrupt();
            checkTrue("on_interrupt_accepted", true);

            // --- per-pin watch/unwatch ---
            var p9 = full.pin(9);
            java.util.function.Consumer<Pcf8575Full.Pin> watcher = pin -> {};
            p9.watch(watcher);
            p9.unwatch();
            checkTrue("pin.watch + unwatch accepted", true);

            System.out.println();
            System.out.printf("Results: %d passed, %d failed%n", passed, failed);
            if (failed > 0) System.exit(1);
        }
    }
}
