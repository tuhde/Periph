///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.AXP2101Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x34").replaceFirst("^0[xX]", ""), 16);
        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new AXP2101Minimal(connection);               // Create AXP2101 driver, (connection)
            for (int i = 0; i < 10; i++) {
                double v = chip.batteryVoltage();                  // Read battery voltage, () → double V
                int pct = chip.batteryPercent();                   // Read state of charge, () → int %
                int state = chip.chargeState();                    // Read charge state, () → int (0 trickle … 5 none)
                boolean vbus = chip.vbusPresent();                 // Check input source, () → boolean
                System.out.printf("%.3f V  %d %%  state=%d  vbus=%b%n", v, pct, state, vbus);
                Thread.sleep(1000);
            }
        }
    }
}
