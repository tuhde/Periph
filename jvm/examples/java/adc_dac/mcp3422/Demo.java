///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.MCP3422Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, MCP3422Full.DEFAULT_ADDRESS)) {                // open I²C bus, device address, (bus, address=0x68) → I2CConnection
            var adc = new MCP3422Full(connection);                                                  // construct driver, (connection) → MCP3422Full
            // --- CH1: thermistor divider at 16 bit / x1 ---
            for (int i = 0; i < 3; i++) {
                int raw = adc.readRaw(0);                                                           // one-shot code, (channel 0–1) → int
                System.out.println("CH1 raw " + raw + " = " + adc.readVoltage(0) + " V");           // one-shot voltage, (channel 0–1) → double V
                Thread.sleep(1000);
            }

            // --- CH2: bridge sensor at 18 bit / x8 ---
            adc.setResolution(18);                                                                  // select 18 bit / 3.75 SPS, (bits) → void
            adc.setGain(8);                                                                         // select PGA x8, (gain) → void
            double volts = adc.readScan(new int[]{1})[0];                                           // scan channels, (channels) → double[] V
            System.out.println("CH2 " + volts * 1e6 + " uV, LSB " + adc.lsb() / adc.getGain() * 1e6 + " uV");// LSB, () → double V

            // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
            adc.setResolution(12);
            adc.setGain(1);
            adc.startContinuous(0);                                                                 // start continuous conversion, (channel) → void
            double[] samples = new double[20];
            long t0 = System.nanoTime();
            for (int i = 0; i < samples.length; i++) {
                samples[i] = adc.readContinuous();                                                  // next fresh voltage, () → double V
            }
            double elapsed = (System.nanoTime() - t0) / 1e9;
            adc.stopContinuous();                                                                   // back to one-shot / standby, () → void
            double min = java.util.Arrays.stream(samples).min().getAsDouble(), max = java.util.Arrays.stream(samples).max().getAsDouble();
            double mean = java.util.Arrays.stream(samples).average().getAsDouble();
            System.out.println("min " + min + " max " + max + " mean " + mean + " V, " + samples.length / elapsed + " samples/s");

            adc.generalCallReset();                                                                 // reset to power-on defaults, () → void
        }
    }
}
