///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gyroscope.L3gd20hMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x6A").replaceFirst("^0[xX]", ""), 16);

        try (var conn = new I2CConnection(bus, addr)) {
            L3gd20hMinimal gyro = new L3gd20hMinimal(conn);

            while (true) {
                float[] xyz = gyro.gyro();  // Read angular rate, () -> float[3] rad/s
                System.out.printf("x=%.3f y=%.3f z=%.3f rad/s%n", xyz[0], xyz[1], xyz[2]);
                Thread.sleep(100);
            }
        }
    }
}