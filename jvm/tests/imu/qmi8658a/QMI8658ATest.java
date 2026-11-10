///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.QMI8658AFull;

public class QMI8658ATest {

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
                System.getenv().getOrDefault("I2C_ADDR", "0x6B").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new QMI8658AFull(connection);   // WHO_AM_I is checked during construction

            double[] a = imu.accel();
            checkTrue("gravity magnitude", norm(a) > 7.0 && norm(a) < 12.6);
            double[] g = imu.gyro();
            checkTrue("gyro finite", Math.abs(g[0]) < 10.0 && Math.abs(g[1]) < 10.0 && Math.abs(g[2]) < 10.0);

            var sample = imu.readAll();
            checkTrue("read_all temp range", sample.tempC > -40.0 && sample.tempC < 85.0);
            checkTrue("temperature matches", Math.abs(imu.temperature() - sample.tempC) < 5.0);

            int[] ra = imu.accelRaw();
            double rawMag = Math.sqrt((double) ra[0] * ra[0] + (double) ra[1] * ra[1] + (double) ra[2] * ra[2]);
            checkTrue("accel_raw gravity", rawMag > 8000.0 && rawMag < 24000.0);
            imu.gyroRaw();

            int ts1 = imu.timestamp();
            Thread.sleep(100);
            checkTrue("timestamp advances", imu.timestamp() != ts1);

            imu.configureAccel(1, 5);
            imu.configureGyro(5, 5);
            Thread.sleep(200);
            checkTrue("accel after reconfig", norm(imu.accel()) > 7.0 && norm(imu.accel()) < 12.6);
            imu.configureAccel(0, 5);
            imu.configureGyro(4, 5);
            imu.configureFilters(true, 3, true, 3);
            Thread.sleep(200);

            imu.configureFifo(2, 1, 8);
            Thread.sleep(300);
            checkTrue("fifo_count non-zero", imu.fifoCount() > 0);
            byte[] data = imu.readFifo(-1);
            checkTrue("read_fifo returns whole frames", data.length > 0 && data.length % 12 == 0);
            imu.resetFifo();
            imu.configureFifo(0, 3, 0);
            Thread.sleep(100);

            var info = imu.deviceInfo();
            checkTrue("device_info", info.firmware.length == 3 && info.usid.length == 6);
            imu.setSensors(true, true, false);

            int status = imu.pollInterrupt();
            checkTrue("poll_interrupt range", status >= 0 && status <= 0x1FF);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
