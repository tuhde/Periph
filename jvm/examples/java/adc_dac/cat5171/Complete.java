///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.CAT5171Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, CAT5171Full.DEFAULT_ADDRESS)) {                // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
            var pot = new CAT5171Full(connection);                                                  // construct driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω) → CAT5171Full
            pot.setWiper(100);                                                                      // Set wiper position, (code 0–255) → void
            // writes [instruction, code] with RS = 0, SD = 0
            System.out.println(pot.getWiper());                                                     // Read wiper position, () → int 0–255
            // bare one-byte read; no instruction byte is sent
            pot.setResistance(50000.0);                                                             // Set W–B resistance, (ohms Ω) → void
            // inverts R = D/256·RAB + RW to the nearest code
            System.out.println(pot.getResistance());                                                // Read W–B resistance, () → double Ω
            // evaluates D/256·RAB + RW for the current code
            pot.setRatio(0.25);                                                                     // Set divider ratio, (ratio 0.0–1.0) → void
            // code = round(ratio × 256), clamped to 255
            System.out.println(pot.getRatio());                                                     // Read divider ratio, () → double
            // code / 256
            pot.setResistanceWa(30000.0);                                                           // Set W–A resistance, (ohms Ω) → void
            // rheostat between W and A with B open
            System.out.println(pot.getResistanceWa());                                              // Read W–A resistance, () → double Ω
            // (256 − D)/256·RAB + RW
            System.out.println(pot.increment(5));                                                   // Step the wiper, (steps=1) → int 0–255
            // read, add, clamp, write; returns the new code
            pot.midscale();                                                                         // Move wiper to midscale, () → void
            // RS = 1 instruction; wiper goes to 0x80
            pot.shutdown();                                                                         // Shut down, () → void
            // SD = 1: A opens, wiper shorts to B, WCR kept
            System.out.println(pot.isShutdown());                                                   // Query shutdown state, () → boolean
            // driver-tracked; the chip cannot report it
            Thread.sleep(1000);
            pot.resume();                                                                           // Leave shutdown, () → void
            // rewrites the kept code with SD = 0
            pot.setRab(50000.0, 120.0);                                                             // Change resistance model, (rAb Ω, rWiper Ω) → void
            // affects conversions only; no bus traffic
            pot.reset();                                                                            // Midscale reset, () → void
            // RS = 1, SD = 0; wiper returns to 0x80
        }
    }
}
