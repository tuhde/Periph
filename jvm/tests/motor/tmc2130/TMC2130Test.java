///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.motor.TMC2130Full;

public class TMC2130Test {

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
            var step = new SysfsOutputPin(27);                                    // Create STEP pin, (gpio=27) → OutputPin
            var dir = new SysfsOutputPin(22);                                     // Create DIR pin, (gpio=22) → OutputPin
            var en = new SysfsOutputPin(17);                                      // Create ENN pin, (gpio=17) → OutputPin
            var motor = new TMC2130Full(connection, step, dir, 0.11, en, null, null);  // Create TMC2130 Full driver, (connection, stepPin, dirPin, rSenseOhm, enPin, diag0, diag1) → TMC2130Full

            var ioin = motor.readIoin();                                          // Read IOIN, () → Ioin
            checkTrue("ioin_version", ioin.version() == 0x11);
            motor.writeRegister(0x13, 600, true);                                 // Write a raw register, (reg, value, verify) → void
            checkTrue("verified_write", true);
            motor.setCurrent(0.5);                                                // Set run current, (rmsA A RMS) → void
            motor.setMicrosteps(16);                                              // Set microstep resolution, (n 1–256) → void
            checkTrue("mres_readback", ((motor.readRegister(0x6C) >> 24) & 0xF) == 4);
            checkTrue("gconf_readback", (motor.readRegister(0x00) & 0x4) == 0x4);
            motor.enable();                                                       // Power stage on, () → void
            motor.move(50, 100);                                                  // Move, (fullSteps, fullStepsPerS full steps/s) → void
            checkTrue("no_fault", motor.readFault() == 0);
            checkTrue("tstep_readable", motor.readTstep() >= 0);
            motor.setStallThreshold(0);                                           // Set StallGuard2 threshold, (sgt -64..63) → void
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
