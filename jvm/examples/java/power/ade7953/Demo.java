///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ade7953Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var ade = new Ade7953Full(connection, 251.0, 30.0);             // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)

            // --- Prepare the chip: configure overcurrent threshold and let the
            //     chip's own IRQ pin alert on overcurrent ---
            ade.configureOvercurrent(40.0);                                  // Configure overcurrent, (threshold) → none

            // --- Sample at 1 Hz and emit one structured line per cycle ---
            // The energy accumulator resets on read by default (RSTREAD = 1), so
            // activeEnergy() returns watt-hours accumulated since the previous
            // call. Callers wanting a running total accumulate the returned deltas
            // themselves.
            System.out.printf("%-10s %-10s %-10s %-12s%n", "V", "A", "W", "Wh/s");
            while (true) {
                double v = ade.voltage();                                    // Read bus voltage, () → double V
                double i = ade.current();                                    // Read load current, () → double A
                double p = ade.activePower();                                // Read active power, () → double W
                double e = ade.activeEnergy();                               // Read active energy, () → double Wh
                System.out.printf("%-10.2f %-10.3f %-10.2f %-12.5f%n", v, i, p, e);
                Thread.sleep(1000);
            }
        }
    }
}