///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.Mcp23008Full;
import it.uhde.periph.chips.io_expander.Mcp23008Minimal;

/**
 * MCP23008 hardware test (Pi, I²C). Wiring for the loopback checks: jumper
 * GP0–GP3 to GP4–GP7 (GPn ↔ GPn+4).
 */
public class Mcp23008Test {

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

            var chip = new Mcp23008Minimal(connection, addr);

            checkEq("shadow init", chip.shadow, 0);
            checkEq("direction init", chip.direction, 0xFF);

            int port = chip.readPort(0);
            checkTrue("readPort in [0, 255]", port >= 0 && port <= 255);

            chip.writePort(0, 0x55);
            checkEq("writePort shadow", chip.shadow, 0x55);
            chip.writePort(0, 0x00);

            var p7 = chip.pin(7);
            p7.setOutput();
            p7.setLow();
            checkEq("pin7 low shadow", chip.shadow & 0x80, 0x00);
            p7.setHigh();
            checkEq("pin7 high shadow", chip.shadow & 0x80, 0x80);
            p7.toggle();
            checkEq("pin7 toggle shadow", chip.shadow & 0x80, 0x00);
            p7.setInput();

            var full = new Mcp23008Full(connection, addr);

            // --- Loopback: GP0-GP3 outputs drive GP4-GP7 inputs ---
            full.writeRegister(0x00, 0xF0);
            checkEq("IODIR readback", full.readRegister(0x00), 0xF0);
            for (int pattern : new int[]{0x05, 0x0A, 0x00}) {
                full.writePort(0, pattern);
                checkEq("loopback 0x" + Integer.toHexString(pattern), (full.readPort(0) >> 4) & 0x0F, pattern);
            }

            full.configurePolarity(0xF0);
            checkEq("polarity inverts inputs", (full.readPort(0) >> 4) & 0x0F, 0x0F);
            full.configurePolarity(0x00);

            full.configurePullup(0xF0);
            checkEq("GPPU readback", full.readRegister(0x06), 0xF0);
            full.configurePullup(0x00);

            full.setDefaultValue(0x00);
            full.enablePinInterrupt(0x10);
            checkEq("GPINTEN readback", full.readRegister(0x02), 0x10);
            full.writePort(0, 0x01);
            int flags = full.pollInterrupt();
            checkTrue("pollInterrupt in [0, 255]", flags >= 0 && flags <= 255);
            int cap = full.readCapture();
            checkTrue("readCapture in [0, 255]", cap >= 0 && cap <= 255);
            full.enablePinInterrupt(0x00);
            full.writePort(0, 0x00);

            full.reset();
            checkEq("reset IODIR", full.readRegister(0x00), 0xFF);
            checkEq("reset OLAT", full.readRegister(0x0A), 0x00);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
