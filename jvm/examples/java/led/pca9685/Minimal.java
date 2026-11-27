///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.Pca9685Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x40"));
        try (var connection = new I2CConnection(bus, addr)) {             // open I²C bus, (bus, address=0x40) → I2CConnection
            var pwm = new Pca9685Minimal(connection);   // construct driver at 1 kHz, (connection) → Pca9685Minimal
            for (int step = 0; step <= 10; step++) {
                pwm.setDuty(0, step / 10.0);   // set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → void
                Thread.sleep(200);
            }
            pwm.setDuty(0, 0.0);
        }
    }
}
