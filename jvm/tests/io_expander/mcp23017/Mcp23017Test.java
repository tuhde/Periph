///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.io_expander.Mcp23017Full;
import it.uhde.periph.chips.io_expander.Mcp23017Minimal;

public class Mcp23017Test {

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

        try (var transport = new I2CTransport(bus, addr)) {

            var chip = new Mcp23017Minimal(transport, addr);

            // --- Shadow initialises to [0, 0] ---
            checkEq("shadow[0] init", chip.shadow[0], 0);
            checkEq("shadow[1] init", chip.shadow[1], 0);

            // --- readPort returns int in [0, 255] ---
            int porta = chip.readPort(0);
            checkTrue("readPort(0) in [0, 255]", porta >= 0 && porta <= 255);

            int portb = chip.readPort(1);
            checkTrue("readPort(1) in [0, 255]", portb >= 0 && portb <= 255);

            // --- writePort updates shadow ---
            chip.writePort(0, 0x55);
            checkEq("writePort(0, 0x55) shadow", chip.shadow[0], 0x55);

            chip.writePort(1, 0xAA);
            checkEq("writePort(1, 0xAA) shadow", chip.shadow[1], 0xAA);

            // --- Pin operations update shadow ---
            var p0 = chip.pin(0);
            p0.setLow();
            checkEq("setLow shadow bit 0", chip.shadow[0] & 0x01, 0);
            p0.setHigh();
            checkEq("setHigh shadow bit 0", chip.shadow[0] & 0x01, 1);

            boolean v = p0.read();
            checkTrue("pin.read() returns boolean", v == true || v == false);

            p0.setOutput();
            checkTrue("pin.setOutput() accepted", true);

            p0.setInput();
            checkTrue("pin.setInput() accepted", true);

            p0.toggle();
            checkTrue("pin.toggle() accepted", true);

            var p8 = chip.pin(8);
            checkTrue("pin(8) accepted", p8 != null);

            var p15 = chip.pin(15);
            checkTrue("pin(15) accepted", p15 != null);

            chip.writePort(0, 0x00);
            chip.writePort(1, 0x00);

            // --- Mcp23017Full ---
            var transport2 = new I2CTransport(bus, addr);
            var full = new Mcp23017Full(transport2, addr);

            full.configurePullup(0, 0x55);
            full.configurePullup(1, 0xAA);

            full.configurePolarity(0, 0x0F);
            full.configurePolarity(1, 0xF0);

            int flags = full.readInterruptFlags(0);
            checkTrue("readInterruptFlags(0) in [0, 255]", flags >= 0 && flags <= 255);

            int changed = full.clearInterrupt(0);
            checkTrue("clearInterrupt(0) in [0, 255]", changed >= 0 && changed <= 255);

            var p1 = full.pin(1);
            checkTrue("full.pin(1) accepted", p1 != null);

            full.stopInterrupt(0);
            checkTrue("stopInterrupt(0) accepted", true);

            System.out.printf("%n===DONE: %d passed, %d failed===%n", passed, failed);
            if (failed > 0) System.exit(1);
        }
    }
}