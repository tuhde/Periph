///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LC709203FMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x0B)) {   // open I²C bus 1, device 0x0B, (bus, address) → I2CConnection
            var gauge = new LC709203FMinimal(connection, 1000, 1);   // construct driver, (connection, capacityMah, profile) → LC709203FMinimal

            for (int i = 0; i < 10; i++) {
                double v = gauge.voltage();     // read cell voltage, () → double V
                double p = gauge.percent();     // read state of charge, () → double %
                double t = gauge.temperature(); // read cell temperature, () → double °C
                System.out.printf("%.3f V  %.1f %%  %.2f C%n", v, p, t);
                Thread.sleep(5000);
            }
        }
    }
}
