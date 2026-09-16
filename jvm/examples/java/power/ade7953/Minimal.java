///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ade7953Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var ade = new Ade7953Minimal(connection, 251.0, 30.0);     // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)
            while (true) {
                double v = ade.voltage();                               // Read bus voltage, () → double V
                double i = ade.current();                               // Read load current, () → double A
                double p = ade.activePower();                           // Read active power, () → double W
                double e = ade.activeEnergy();                          // Read active energy, () → double Wh
                System.out.printf("V=%.2f I=%.3f P=%.2f E=%.4f%n", v, i, p, e);
                Thread.sleep(1000);
            }
        }
    }
}