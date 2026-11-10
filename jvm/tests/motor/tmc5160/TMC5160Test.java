///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.motor.TMC5160Full;

public class TMC5160Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 3, 1_000_000)) {        // Create SPI connection, (bus, dev, mode=3, maxSpeedHz=1e6) → SPIConnection
            var en = new SysfsOutputPin(17);                                      // Create DRV_ENN pin, (gpio=17) → OutputPin
            var motor = new TMC5160Full(connection, 0.075, en, null, null);       // Create TMC5160 Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → TMC5160Full

            var ioin = motor.readIoin();                                          // Read IOIN, () → Ioin
            checkTrue("ioin_version", ioin.version() == 0x30);
            motor.writeRegister(0x13, 600, true);                                 // Write a raw register, (reg, value, verify) → void
            checkTrue("verified_write", true);
            motor.setMicrosteps(16);                                              // Set microstep resolution, (n 1–256) → void
            checkTrue("mres_readback", ((motor.readRegister(0x6C) >> 24) & 0xF) == 4);
            checkTrue("gconf_readback", (motor.readRegister(0x00) & 0x4) == 0x4);
            motor.setMicrosteps(256);
            motor.enable();                                                       // Power stage on, () → void
            motor.setPosition(0.0);                                               // Define position, (position full steps) → void
            motor.moveTo(100.0);                                                  // Start positioning move, (position full steps) → void
            motor.waitUntilReached(0.0);                                          // Wait for the ramp generator, (timeoutS=0 s) → void
            checkTrue("move_reached", Math.abs(motor.position() - 100.0) < 1.0);
            checkTrue("no_fault", motor.readFault() == 0);
            int sg = motor.readStallguard();                                      // Read StallGuard2 result, () → int
            checkTrue("sg_result_range", sg >= 0 && sg <= 1023);
            motor.disable();                                                      // Power stage off, () → void
            checkTrue("status_readable", motor.readStatus() != null);
            checkTrue("gstat_readable", motor.readGlobalStatus() != null);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
