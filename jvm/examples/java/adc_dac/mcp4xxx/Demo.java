///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.MCP4XXXFull;
import it.uhde.periph.chips.adc_dac.MCP4XXXMinimal;

public class Demo {
    public static void main(String[] args) throws Exception {
        String part = args.length > 0 ? args[0] : "MCP4532";   // any of MCP4531 ... MCP4662
        try (var connection = new I2CConnection(1, MCP4XXXMinimal.defaultAddress(part))) {  // open I²C bus 1, device at the part's default address, (bus, address) → I2CConnection
            var pot = new MCP4XXXFull(connection, part, 10000.0, 75.0);  // construct driver, (connection, part, rAb=10000 Ω, rWiper=75 Ω, hvc=null, hvcSettleMs=1) → MCP4XXXFull
            pot.init();                                                  // bus check, () → void

            // --- Identify the family member ---
            // One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
            System.out.printf("%s %d network(s) %d steps %s %s%n", part, pot.channels(), pot.steps(),    // part properties, () → int
                    pot.isPotentiometer() ? "pot" : "rheostat", pot.isNonvolatile() ? "EEPROM" : "RAM");
            System.out.printf("power-up wiper %d %.1f ohm%n", pot.getWiper(0), pot.getResistance(0));   // read wiper, (channel=0) → int

            // --- Set 2.2 kΩ and report the quantisation error ---
            // The wiper snaps to the nearest step of RAB / max code.
            pot.setResistance(2200.0, 0);                                // set W-B resistance, (ohms, channel=0) → void
            double actual = pot.getResistance(0);                        // read W-B resistance, (channel=0) → double Ω
            System.out.printf("target=2200 ohm code=%d actual=%.1f ohm error=%.1f ohm%n", pot.getWiper(0), actual, actual - 2200.0);

            // --- Step the wiper up and down ---
            // increment / decrement send one command byte per step in one transaction and saturate at full scale and zero scale.
            int[] moves = {8, 8, -4, 10};
            for (int m : moves) {
                int code = m > 0 ? pot.increment(m, 0) : pot.decrement(-m, 0);   // step wiper, (steps=1, channel=0) → int
                System.out.printf("%+d -> code=%d  R_WB=%.1f ohm%n", m, code, pot.getResistance(0));
            }

            // --- Ratios ---
            // The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
            for (double ratio : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
                pot.setRatio(ratio, 0);                                  // set wiper ratio, (ratio 0.0-1.0, channel=0) → void
                String wa = pot.isPotentiometer() ? String.format("  R_WA=%.1f", pot.getResistanceWa(0)) : "";  // read W-A resistance, (channel=0) → double Ω
                System.out.printf("ratio=%.2f code=%d R_WB=%.1f%s%n", ratio, pot.getWiper(0), pot.getResistance(0), wa);
            }

            // --- Dual parts: drive both networks ---
            if (pot.channels() == 2) {
                pot.setBoth(32, 96);                                     // set both wipers, (code0, code1) → void
                System.out.printf("both: %d %d -> %d %d%n", pot.getWiper(0), pot.getWiper(1), pot.increment(5, 1), pot.decrement(2, 1));
            }

            // --- Shutdown and terminal disconnect ---
            // Shutdown ties W to B and opens A without losing the wiper; setTerminals(w = false) floats W.
            pot.shutdown(0);                                             // shut network down, (channel=0) → void
            System.out.printf("shutdown=%b code=%d%n", pot.isShutdown(0), pot.getWiper(0));  // read state, (channel=0) → boolean
            Thread.sleep(1000);
            pot.resume(0);                                               // leave shutdown, (channel=0) → void
            pot.setTerminals(true, false, true, 0);                      // disconnect the wiper, (a, w, b, channel=0) → void
            System.out.println("terminals " + java.util.Arrays.toString(pot.getTerminals(0)));  // read terminals, (channel=0) → (a, w, b)
            pot.setTerminals(true, true, true, 0);                       // reconnect all terminals, (a, w, b, channel=0) → void

            // --- Non-volatile parts: EEPROM wiper and data ---
            // storeWiper makes the setting survive power cycles; every EEPROM write uses one of the
            // 1 000 000 write cycles, so do not do this in a loop.
            if (pot.isNonvolatile()) {
                System.out.println("busy=" + pot.readStatus().eepromBusy);   // read STATUS, () → Status
                System.out.println("stored " + pot.storeWiper(0));          // store the wiper in EEPROM, (channel=0) → int
                pot.writeData(0, 0x155);                                 // write calibration marker, (index 0-9, value 0-0x1FF) → void
                System.out.printf("data0 %#x nv wiper %d%n", pot.readData(0), pot.getNvWiper(0));  // read back, (index) → int
            }

            // --- Back to the power-up state ---
            pot.reset();                                                 // emulate power-on reset, () → void
            System.out.println("after reset: " + pot.getWiper(0));
        }
    }
}
