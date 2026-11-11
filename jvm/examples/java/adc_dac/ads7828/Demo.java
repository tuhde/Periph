///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS7828Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS7828Full.DEFAULT_ADDRESS)) {             // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS7828Full(connection);                                                  // construct driver, (connection, vref=null → internal 2.5 V) → ADS7828Full
            adc.init();                                                                             // Enable internal reference, () → void
            final double divider = 2.0;   // inputs sit behind 2:1 dividers (rails up to 5 V)
            int[] channels = {0, 1, 2, 3, 4, 5, 6, 7};

            // --- Eight-channel supply monitor ---
            // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
            // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
            double[] vmin = new double[8];
            double[] vmax = new double[8];
            java.util.Arrays.fill(vmin, 99.0);
            for (int n = 0; n < 5; n++) {
                int[] raws = adc.readScan(channels);                                                // Scan channels, (channels int[]) → int[]
                for (int ch = 0; ch < 8; ch++) {
                    double volts = raws[ch] * adc.lsb() * divider;
                    vmin[ch] = Math.min(vmin[ch], volts);
                    vmax[ch] = Math.max(vmax[ch], volts);
                    System.out.println("ch" + ch + " raw=" + raws[ch] + " " + volts + " V");
                }
                Thread.sleep(1000);
            }
            System.out.println("min " + java.util.Arrays.toString(vmin) + " max " + java.util.Arrays.toString(vmax));

            // --- Read a bridge sensor on CH0/CH1 ---
            // The signed differential read does two conversions (normal and inverted
            // polarity) so the sign of the bridge output is preserved.
            double mv = adc.readSignedDifferential(0) * 1000.0;                                     // Read pair 0 signed, (pair 0–3) → double V
            System.out.println("bridge " + mv + " mV");

            // --- Second scan against an external 5.0 V reference ---
            // Switching to a 5 V external reference gives full-scale range up to VDD.
            adc.setReference(5.0);                                                                  // Use external reference, (vref Double V; null = internal) → void
            int[] ext = adc.readScan(channels);                                                     // Scan channels, (channels int[]) → int[]
            System.out.println("ext 5 V scan " + java.util.Arrays.toString(ext));

            // --- Finish in full power-down ---
            adc.powerDown();                                                                        // Power down completely, () → void
        }
    }
}
