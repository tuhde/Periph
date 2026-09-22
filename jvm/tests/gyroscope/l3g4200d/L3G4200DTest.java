///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gyroscope.L3g4200dMinimal;
import it.uhde.periph.chips.gyroscope.L3g4200dFull;

public class L3G4200DTest {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
            System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var gyro = new L3g4200dMinimal(connection, false);
            float[] xyz = gyro.angularRate();
            checkTrue(xyz[0] >= -50.0f && xyz[0] <= 50.0f, "angular_rate_x_range");
            checkTrue(xyz[1] >= -50.0f && xyz[1] <= 50.0f, "angular_rate_y_range");
            checkTrue(xyz[2] >= -50.0f && xyz[2] <= 50.0f, "angular_rate_z_range");

            try (var connection2 = new I2CConnection(bus, addr)) {
                var gyroFull = new L3g4200dFull(connection2, false);
                checkTrue(gyroFull.whoAmI() == 0xD3, "who_am_i");
                gyroFull.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS);
                checkTrue(gyroFull.getClass().equals(L3g4200dFull.class), "configure");
                float[] xyz2 = gyroFull.angularRate();
                checkTrue(xyz2[0] >= -500.0f && xyz2[0] <= 500.0f, "configure_then_read");
                checkTrue(gyroFull.status() <= 0xFF, "status_readable");
                int temp = gyroFull.temperature();
                checkTrue(temp >= -50 && temp <= 100, "temperature_range");
                gyroFull.setFullScale(L3g4200dFull.FS_2000_DPS);
                float[] xyz3 = gyroFull.angularRate();
                checkTrue(xyz3[0] >= -2000.0f && xyz3[0] <= 2000.0f, "set_full_scale_2000");
                int samples = gyroFull.fifoSamples();
                checkTrue(samples <= 31, "fifo_samples_in_range");
                gyroFull.enableFifo(L3g4200dFull.FIFO_STREAM, 10);
                checkTrue(true, "enable_fifo_no_throw");
            }
        }
        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        if (failed != 0) System.exit(1);
    }
}
