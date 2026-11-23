///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.Pca9685Minimal;
import it.uhde.periph.chips.led.Pca9685Full;

public class Pca9685Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    interface Action { void run() throws Exception; }

    static boolean throwsIllegal(Action a) throws Exception {
        try { a.run(); } catch (IllegalArgumentException e) { return true; }
        return false;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x40"));

        try (var connection = new I2CConnection(bus, addr)) {
            var pwm = new Pca9685Minimal(connection);
            checkTrue("constants", Pca9685Minimal.CHANNELS == 16);
            pwm.setDuty(0, 0.5);
            pwm.setDuty(15, 0.0);
            pwm.setDuty(1, 1.0);
            checkTrue("set_duty", true);
            checkTrue("set_duty_range", throwsIllegal(() -> pwm.setDuty(16, 0.5)) && throwsIllegal(() -> pwm.setDuty(0, 1.5)));

            var full = new Pca9685Full(connection);
            full.setPwm(2, 409, 1228);
            int[] p = full.getPwm(2);
            checkTrue("set_get_pwm", p[0] == 409 && p[1] == 1228);
            checkTrue("pwm_range", throwsIllegal(() -> full.setPwm(2, 5, 5)));
            full.setFullOn(3);
            full.setFullOff(3);
            checkTrue("full_on_off", true);
            full.setAllDuty(0.1);
            full.setAllPwm(0, 2048);
            full.setAllDuty(0.0);
            checkTrue("all", true);
            full.setFrequency(50);
            checkTrue("frequency", Math.abs(full.getFrequency() - 50.0) < 1.0);
            full.setPulseWidth(15, 0.0015);
            checkTrue("pulse_width", true);
            full.sleep();
            full.wake();
            full.restart();
            checkTrue("sleep_wake", true);
            full.setOutputMode(false, false, Pca9685Full.OFF_LOW);
            full.setUpdateOnAck(false);
            checkTrue("output_mode", true);
            full.setAllcall(false);
            full.setSubaddress(1, 0x71, false);
            checkTrue("addresses", true);
            int[] m = full.readMode();
            checkTrue("read_mode", (m[0] & 0x10) == 0 && (m[1] & 0x04) == 0x04);
            full.reset();
            checkTrue("reset", true);
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
