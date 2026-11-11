///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.PCA9536Full;
import it.uhde.periph.chips.io_expander.PCA9536Minimal;

/**
 * PCA9536 hardware test (Pi, I²C). Wiring for the loopback checks: jumper
 * P0–P1 to P2–P3 (Pn ↔ Pn+2).
 */
public class PCA9536Test {

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
                System.getenv().getOrDefault("I2C_ADDR", "0x41").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {

            var chip = new PCA9536Minimal(connection, addr);

            checkEq("shadow init", chip.shadow, 0xFF);
            checkEq("direction init", chip.direction, 0xFF);

            int port = chip.readPort(0);
            checkTrue("readPort in [0, 15]", port >= 0 && port <= 15);

            chip.writePort(0, 0x05);
            checkEq("writePort shadow", chip.shadow & 0x0F, 0x05);
            chip.writePort(0, 0x0F);

            var p3 = chip.pin(3);
            p3.setOutput();
            p3.setLow();
            checkEq("pin3 low shadow", chip.shadow & 0x08, 0x00);
            p3.setHigh();
            checkEq("pin3 high shadow", chip.shadow & 0x08, 0x08);
            p3.toggle();
            checkEq("pin3 toggle shadow", chip.shadow & 0x08, 0x00);
            p3.setInput();

            var full = new PCA9536Full(connection, addr);

            // --- Loopback: P0-P1 outputs drive P2-P3 inputs ---
            full.setDirection(0x0C);
            checkEq("CONFIG readback", full.readRegister(0x03) & 0x0F, 0x0C);
            for (int pattern : new int[]{0x01, 0x02, 0x00}) {
                full.writePort(0, pattern);
                checkEq("loopback 0x" + Integer.toHexString(pattern), (full.readPort(0) >> 2) & 0x03, pattern);
            }

            full.configurePolarity(0x0C);
            checkEq("POLARITY readback", full.readRegister(0x02) & 0x0F, 0x0C);
            checkEq("polarity inverts inputs", (full.readPort(0) >> 2) & 0x03, 0x03);
            full.configurePolarity(0x00);

            full.pollChanges();
            full.writePort(0, 0x01);
            checkEq("pollChanges changed mask", full.pollChanges(), 0x04);
            full.writePort(0, 0x00);

            full.reset();
            checkEq("reset CONFIG", full.readRegister(0x03) & 0x0F, 0x0F);
            checkEq("reset OUTPUT", full.readRegister(0x01) & 0x0F, 0x0F);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
