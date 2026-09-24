///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.motor.DRV8830Minimal;
import it.uhde.periph.chips.motor.DRV8830Full;

public class DRV8830Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x60"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new DRV8830Minimal(connection);
            checkTrue(true, "construct_minimal");
            minimal.stop();

            var motor = new DRV8830Full(connection);
            motor.drive(2.0);
            var out = motor.readOutput();
            checkTrue(out.direction() == DRV8830Full.Direction.FORWARD, "drive_forward_direction");
            checkTrue(Math.abs(out.voltage() - 2.0) < 0.1, "drive_forward_voltage");

            motor.drive(-1.0);
            checkTrue(motor.readOutput().direction() == DRV8830Full.Direction.REVERSE, "drive_reverse_direction");

            motor.brake();
            checkTrue(motor.readOutput().direction() == DRV8830Full.Direction.BRAKE, "brake_direction");

            motor.stop();
            checkTrue(motor.readOutput().direction() == DRV8830Full.Direction.COAST, "stop_direction");

            motor.clearFault();
            checkTrue(!motor.readFault().fault(), "clear_fault");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
