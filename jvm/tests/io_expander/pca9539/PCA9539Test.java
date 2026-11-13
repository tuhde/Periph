///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.PCA9539Full;
import it.uhde.periph.chips.io_expander.PCA9539Minimal;

/**
 * PCA9539 hardware test (Pi, I²C). Wiring for the loopback checks: jumper
 * port 0 to port 1 (P0_n ↔ P1_n).
 */
public class PCA9539Test {

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
                System.getenv().getOrDefault("I2C_ADDR", "0x74").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {

            var chip = new PCA9539Minimal(connection, addr);

            checkEq("shadow init", chip.shadow, 0xFFFF);
            checkEq("direction init", chip.direction, 0xFFFF);

            checkTrue("readPort(0) in [0, 255]", chip.readPort(0) >= 0 && chip.readPort(0) <= 255);
            checkTrue("readPort(1) in [0, 255]", chip.readPort(1) >= 0 && chip.readPort(1) <= 255);

            chip.writePort(0, 0x55);
            chip.writePort(1, 0xAA);
            checkEq("writePort shadow", chip.shadow, 0xAA55);
            chip.writePort(0, 0xFF);
            chip.writePort(1, 0xFF);

            var p15 = chip.pin(15);
            p15.setOutput();
            p15.setLow();
            checkEq("pin15 low shadow", chip.shadow & 0x8000, 0x0000);
            p15.setHigh();
            checkEq("pin15 high shadow", chip.shadow & 0x8000, 0x8000);
            p15.toggle();
            checkEq("pin15 toggle shadow", chip.shadow & 0x8000, 0x0000);
            p15.setInput();

            var full = new PCA9539Full(connection, addr);

            // --- Loopback: port 0 outputs drive port 1 inputs ---
            full.setDirection(0xFF00);
            checkEq("CONFIG0 readback", full.readRegister(0x06), 0x00);
            checkEq("CONFIG1 readback", full.readRegister(0x07), 0xFF);
            for (int pattern : new int[]{0x5A, 0xA5, 0x00}) {
                full.writePort(0, pattern);
                checkEq("loopback 0x" + Integer.toHexString(pattern), full.readPort(1), pattern);
            }
            full.writeAll(0x00FF);
            checkEq("readAll pair", full.readAll(), 0xFFFF);
            full.writeAll(0x0000);

            full.configurePolarity(0xFF00);
            checkEq("POLARITY1 readback", full.readRegister(0x05), 0xFF);
            checkEq("polarity inverts inputs", full.readPort(1), 0xFF);
            full.configurePolarity(0x0000);

            full.pollInterrupt();
            full.writePort(0, 0x01);
            checkEq("pollInterrupt changed mask", full.pollInterrupt(), 0x0100);
            full.writePort(0, 0x00);

            full.reset();
            checkEq("reset CONFIG0", full.readRegister(0x06), 0xFF);
            checkEq("reset CONFIG1", full.readRegister(0x07), 0xFF);
            checkEq("reset OUTPUT0", full.readRegister(0x02), 0xFF);
            checkEq("reset OUTPUT1", full.readRegister(0x03), 0xFF);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
