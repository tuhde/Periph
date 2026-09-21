///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gyroscope.L3gd20hFull;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x6A").replaceFirst("^0[xX]", ""), 16);

        try (var conn = new I2CConnection(bus, addr)) {
            L3gd20hFull gyro = new L3gd20hFull(conn);

            // --- Configure for shake detection at 190 Hz, ±500 dps ---
            // 190 Hz ODR provides good temporal resolution for shake detection;
            // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
            // detecting moderate to strong motion without clipping.
            gyro.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS);

            System.out.println("L3GD20H shake detector running. Shake the device...");

            while (true) {
                if (gyro.dataReady()) {                       // Check data ready
                    float[] xyz = gyro.gyro();                // Read angular rate
                    float magnitude = (float) Math.sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2]);
                    if (magnitude > 1.0) {
                        System.out.printf("SHAKE DETECTED: mag=%.3f (x=%.3f y=%.3f z=%.3f)%n", magnitude, xyz[0], xyz[1], xyz[2]);
                    } else {
                        System.out.printf("x=%.3f y=%.3f z=%.3f mag=%.3f%n", xyz[0], xyz[1], xyz[2], magnitude);
                    }
                }
            }
        }
    }
}