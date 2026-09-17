///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ade7953Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var ade = new Ade7953Full(connection, 251.0, 30.0);          // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)

            System.out.printf("version: 0x%02X%n", ade.version());       // Read silicon version, () → int
            System.out.printf("V=%.2f%n",   ade.voltage());              // Read bus voltage, () → double V
                                                                          // converts raw VRMS to volts using voltage_gain
            System.out.printf("I_a=%.3f%n", ade.current());              // Read load current, () → double A
                                                                          // converts raw IRMSA to amperes using current_gain
            System.out.printf("P_a=%.2f%n", ade.activePower());          // Read active power, () → double W
                                                                          // converts raw AWATT (instantaneous, 6.99 kHz) to watts
            System.out.printf("E_a=%.4f%n", ade.activeEnergy());         // Read active energy, () → double Wh
                                                                          // converts raw AENERGYA accumulated LSBs to watt-hours
            System.out.printf("PF=%.3f%n",  ade.powerFactor());          // Read power factor, () → double ratio
                                                                          // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
            System.out.printf("f=%.2f%n",    ade.lineFrequency());        // Read line frequency, () → double Hz

            ade.configureChannelB(30.0);                                  // Set Channel B calibration, (current_gain_b) → none
            System.out.printf("I_b=%.3f%n", ade.currentB());              // Read Current Channel B, () → double A

            ade.configureOvervoltage(260.0);                              // Configure overvoltage, (threshold) → none
                                                                          // threshold in volts (same scale as voltage())
            ade.configureOvercurrent(40.0);                               // Configure overcurrent, (threshold) → none
                                                                          // threshold in amperes; applies to BOTH current channels

            ade.reset();                                                  // Software reset, () → none
                                                                          // waits 110 ms then re-runs the mandatory power-up sequence
        }
    }
}