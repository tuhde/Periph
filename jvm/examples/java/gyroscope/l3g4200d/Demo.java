///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gyroscope.L3g4200dFull;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x68)) {       // open I²C bus 1, device 0x68
            // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
            var gyro = new L3g4200dFull(connection, false);         // construct driver
            gyro.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS);  // configure chip, (odr, bandwidth, fullScale) → void
            gyro.enableHighpass(0, 4);                              // enable high-pass, (mode=0, cutoff=4) → void
                                                                    // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
            gyro.enableFifo(L3g4200dFull.FIFO_STREAM, 10);          // enable FIFO, (mode=stream=2, watermark=10) → void

            float threshold = (float) (90.0 * Math.PI / 180.0);
            int alerts = 0;

            // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
            for (int n = 0; n < 50; n++) {
                while (gyro.fifoSamples() < 10) {                  // read FIFO count, () → int
                    Thread.sleep(5);
                }
                var burst = gyro.readFifo();                       // drain FIFO, () → List<float[]>
                if (burst.isEmpty()) continue;
                float mx = 0, my = 0, mz = 0;
                for (var s : burst) { mx += s[0]; my += s[1]; mz += s[2]; }
                int count = burst.size();
                mx /= count; my /= count; mz /= count;
                if (Math.abs(mx) > threshold || Math.abs(my) > threshold || Math.abs(mz) > threshold) {
                    alerts++;
                    System.out.printf("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s%n", mx, my, mz);
                } else {
                    System.out.printf("       X=%.2f Y=%.2f Z=%.2f rad/s%n", mx, my, mz);
                }
                Thread.sleep(20);
            }

            System.out.printf("Total alerts: %d / 50%n", alerts);
        }
    }
}
