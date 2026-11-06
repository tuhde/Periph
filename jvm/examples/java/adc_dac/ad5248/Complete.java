///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5248Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x2C)) {          // open I²C bus 1, device 0x2C, (bus, address) → I2CConnection
            var pot = new AD5248Full(connection, 10000.0, 160.0);    // construct driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Full

            // --- Wiper position ---
            pot.setWiper(0, 100);                                // set wiper, (channel 0-1, code 0-255) → void
                                                                 // writes [channel << 7, code]; code clamped to 0-255
            int code = pot.getWiper(0);                          // read wiper, (channel 0-1) → int 0–255
                                                                 // sends the instruction byte alone, then reads one byte
            System.out.printf("code=%d%n", code);
            pot.setBoth(10, 20);                                 // set both wipers, (code1 0-255, code2 0-255) → void
                                                                 // two transactions, one per channel
            // --- Resistance ---
            pot.setResistance(1, 2200.0);                        // set W-B resistance, (channel 0-1, ohms) → void
                                                                 // nearest code of R = code/256*RAB + 2*RW, clamped
            double ohms = pot.getResistance(1);                  // read W-B resistance, (channel 0-1) → double Ω
                                                                 // code/256*RAB + 2*RW
            System.out.printf("ch1 ohm=%.1f%n", ohms);
            // --- Ratio and increment ---
            pot.setRatio(0, 0.5);                                // set wiper ratio, (channel 0-1, ratio 0.0-1.0) → void
                                                                 // code = round(ratio*256) clamped to 255; needs terminal A wired
            double ratio = pot.getRatio(0);                      // read wiper ratio, (channel 0-1) → double 0.0–1.0
                                                                 // code / 256
            System.out.printf("ratio=%.3f%n", ratio);
            int next = pot.increment(0, 5);                      // step wiper, (channel 0-1, steps) → int 0–255
                                                                 // read-modify-write, clamped to 0-255; negative steps decrement
            System.out.printf("next=%d%n", next);
            // --- Shutdown ---
            pot.shutdown(1);                                     // shut channel down, (channel 0-1) → void
                                                                 // opens terminal A, shorts wiper to B, RDAC kept
            boolean down = pot.isShutdown(1);                    // query driver-tracked state, (channel 0-1) → boolean
                                                                 // the chip cannot be read back
            System.out.printf("shutdown=%s%n", down);
            pot.resume(1);                                       // leave shutdown, (channel 0-1) → void
                                                                 // rewrites the RDAC with SD = 0
            // --- Model and reset ---
            pot.setRab(10000.0, 160.0);                          // set resistance model, (rAb Ω, rWiper Ω) → void
                                                                 // no I²C traffic; affects conversions only
            pot.reset();                                         // reset to power-up preset, () → void
                                                                 // writes midscale 0x80 to both channels with SD = 0
        }
    }
}
