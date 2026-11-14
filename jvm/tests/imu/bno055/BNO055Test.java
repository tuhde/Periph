///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.BNO055Full;

public class BNO055Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x29").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new BNO055Full(connection);   // CHIP_ID is checked during construction

            int[] rev = imu.revisions();
            checkTrue("chip_id", rev[0] == 0xA0);
            checkTrue("sensor ids", rev[1] == 0xFB && rev[2] == 0x32 && rev[3] == 0x0F);
            checkTrue("mode ndof", imu.getMode() == 0x0C);

            double[] e = imu.euler();
            checkTrue("euler range", e[0] >= 0 && e[0] < 6.5 && e[1] > -1.6 && e[1] < 1.6 && e[2] > -3.2 && e[2] < 3.2);
            double[] q = imu.quaternion();
            double qn = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            checkTrue("quaternion unit", Math.abs(qn - 1.0) < 0.05);
            checkTrue("gravity ~1 g", norm(imu.gravity()) > 8.5 && norm(imu.gravity()) < 11.0);
            checkTrue("linear acceleration small", norm(imu.linearAcceleration()) < 5.0);
            checkTrue("temperature range", imu.temperature() > -40 && imu.temperature() < 85);
            int[] cal = imu.calibrationStatus();
            checkTrue("calibration status range", cal[0] <= 3 && cal[1] <= 3 && cal[2] <= 3 && cal[3] <= 3);
            int[] ss = imu.systemStatus();
            checkTrue("system status", ss[0] == 5 && ss[1] == 0);
            checkTrue("unique id length", imu.uniqueId().length == 16);
            checkTrue("calibration length", imu.getCalibration().length == 22);

            imu.setMode(BNO055Full.MODE_AMG);
            checkTrue("mode amg", imu.getMode() == 0x07);
            checkTrue("accel magnitude ~1 g", norm(imu.acceleration()) > 8.0 && norm(imu.acceleration()) < 11.5);
            checkTrue("mag field range", norm(imu.magneticField()) > 5.0 && norm(imu.magneticField()) < 200.0);
            imu.setMode(BNO055Full.MODE_NDOF);
            checkTrue("mode ndof again", imu.getMode() == 0x0C);

            boolean[] st = imu.selfTest();
            checkTrue("self test passes", st[0] && st[1] && st[2] && st[3]);
            checkTrue("mode after self test", imu.getMode() == 0x0C);

            imu.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY);
            int ints = imu.pollInterrupt();
            checkTrue("poll interrupt", ints >= 0 && ints <= 0xFF);
            imu.disableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
