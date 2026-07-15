///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.imu.Mpu6050Full;

public class Mpu6050Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {
            var imu = new Mpu6050Full(transport);

            double[] a = imu.accel();
            checkTrue("accel_x finite", a[0] > -200.0 && a[0] < 200.0);
            checkTrue("accel_y finite", a[1] > -200.0 && a[1] < 200.0);
            checkTrue("accel_z finite", a[2] > -200.0 && a[2] < 200.0);

            double[] g = imu.gyro();
            checkTrue("gyro_x finite", g[0] > -100.0 && g[0] < 100.0);
            checkTrue("gyro_y finite", g[1] > -100.0 && g[1] < 100.0);
            checkTrue("gyro_z finite", g[2] > -100.0 && g[2] < 100.0);

            double t = imu.temperature();
            checkTrue("temperature range", t > -40.0 && t < 85.0);

            int[] ra = imu.accelRaw();
            checkTrue("accel_raw_x range", ra[0] >= -32768 && ra[0] <= 32767);

            int[] rg = imu.gyroRaw();
            checkTrue("gyro_raw_x range", rg[0] >= -32768 && rg[0] <= 32767);

            imu.configureGyro(1);
            imu.configureAccel(1);
            double[] a2 = imu.accel();
            checkTrue("accel after reconfig", a2[0] > -200.0 && a2[0] < 200.0);

            imu.configureDlpf(4);
            imu.configureSampleRate(9);

            imu.setSleep(true);
            Thread.sleep(10);
            imu.setSleep(false);
            Thread.sleep(50);
            double[] a3 = imu.accel();
            checkTrue("accel after wake", a3[0] > -200.0 && a3[0] < 200.0);

            imu.setStandby(true, false, false, false, false, false);
            imu.setStandby(false, false, false, false, false, false);

            imu.resetFifo();
            imu.enableFifo(true, true, false);
            Thread.sleep(50);
            int count = imu.fifoCount();
            checkTrue("fifo_count > 0", count > 0);
            byte[] data = imu.readFifo();
            checkTrue("read_fifo matches count", data.length == count);

            imu.resetFifo();

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
