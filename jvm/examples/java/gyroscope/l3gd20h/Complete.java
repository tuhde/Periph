///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gyroscope.L3gd20hFull;
import java.util.List;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x6A").replaceFirst("^0[xX]", ""), 16);

        try (var conn = new I2CConnection(bus, addr)) {
            L3gd20hFull gyro = new L3gd20hFull(conn);

            gyro.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS);  // Configure

            gyro.configureHpFilter(L3gd20hFull.HPM_NORMAL, 0);  // Configure HPF
            gyro.enableHpFilter(true);                           // Enable HPF

            gyro.configureFifo(L3gd20hFull.FIFO_FIFO, 10);       // Configure FIFO
            gyro.enableFifo(true);                                // Enable FIFO

            gyro.setPowerMode(L3gd20hFull.POWER_NORMAL);         // Set power mode

            int who = gyro.whoAmI();                             // Read WHO_AM_I
            System.out.printf("WHO_AM_I: 0x%02X%n", who);

            int temp = gyro.temperature();                       // Read temperature
            System.out.printf("Temperature: %d%n", temp);

            while (true) {
                if (gyro.dataReady()) {                          // Check data ready
                    float[] xyz = gyro.gyro();                   // Read angular rate
                    System.out.printf("x=%.3f y=%.3f z=%.3f rad/s%n", xyz[0], xyz[1], xyz[2]);

                    short[] raw = gyro.gyroRaw();                // Read raw

                    int level = gyro.fifoLevel();                // FIFO level
                    if (level > 0) {
                        List<float[]> samples = gyro.readFifo(); // Read FIFO
                        System.out.printf("FIFO: %d samples%n", samples.size());
                    }
                }
                Thread.sleep(10);
            }
        }
    }
}