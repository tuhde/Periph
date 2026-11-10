///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.ICM20948Full;

public class ICM20948Test {

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
                System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new ICM20948Full(connection);   // WHO_AM_I is checked during construction

            double[] a = imu.accel();
            checkTrue("accel finite", a[0] > -200.0 && a[0] < 200.0 && a[1] > -200.0 && a[1] < 200.0
                    && a[2] > -200.0 && a[2] < 200.0);
            checkTrue("gravity magnitude", norm(a) > 5.0 && norm(a) < 15.0);

            double[] g = imu.gyro();
            checkTrue("gyro finite", g[0] > -100.0 && g[0] < 100.0 && g[1] > -100.0 && g[1] < 100.0
                    && g[2] > -100.0 && g[2] < 100.0);

            double t = imu.temperature();
            checkTrue("temperature range", t > -40.0 && t < 85.0);

            var sample = imu.readAll();
            checkTrue("read_all temp range", sample.tempC > -40.0 && sample.tempC < 85.0);

            int[] ra = imu.accelRaw();
            checkTrue("accel_raw range", ra[0] >= -32768 && ra[0] <= 32767);
            int[] rg = imu.gyroRaw();
            checkTrue("gyro_raw range", rg[0] >= -32768 && rg[0] <= 32767);

            imu.configureGyro(1);
            imu.configureAccel(1);
            double[] a2 = imu.accel();
            checkTrue("accel after reconfig", a2[0] > -200.0 && a2[0] < 200.0);

            imu.configureFilters(4, 4, true, true);
            imu.configureSampleRate(9, 9);
            int[] off = imu.getAccelOffset();
            checkTrue("accel offset range", off[0] >= -16384 && off[0] <= 16383);

            imu.enableMag();
            Thread.sleep(150);
            double[] m = imu.mag();
            checkTrue("mag magnitude", norm(m) > 5.0 && norm(m) < 150.0);
            checkTrue("aux_read WIA2", imu.auxReadByte(0x0C, 0x01) == 0x09);

            imu.setSleep(true);
            Thread.sleep(10);
            imu.setSleep(false);
            Thread.sleep(50);
            double[] a3 = imu.accel();
            checkTrue("accel after wake", a3[0] > -200.0 && a3[0] < 200.0);

            imu.enableFifo(true, true, false);
            Thread.sleep(50);
            int count = imu.fifoCount();
            checkTrue("fifo_count > 0", count > 0);
            byte[] data = imu.readFifo(-1);
            checkTrue("read_fifo returns bytes", data.length > 0);
            imu.resetFifo();

            imu.setWakeOnMotion(200.0, true);
            int status = imu.pollInterrupt();
            checkTrue("poll_interrupt range", status >= 0 && status <= 0x1F);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
