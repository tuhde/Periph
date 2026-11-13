///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.MCP3422Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, MCP3422Full.DEFAULT_ADDRESS)) {                // open I²C bus, device address, (bus, address=0x68) → I2CConnection
            var adc = new MCP3422Full(connection);                                                  // construct driver, (connection) → MCP3422Full
            System.out.println(adc.readRaw(0));                                                     // one-shot code, (channel 0–1) → int
            System.out.println(adc.readVoltage(1));                                                 // one-shot voltage, (channel 0–1) → double V
            System.out.println(adc.readAll());                                                      // both channels, () → double[] V
            System.out.println(adc.readScan(new int[]{0, 1, 0}));                                   // scan a list of channels, (channels) → double[] V

            adc.setResolution(18);                                                                  // select 18 bit / 3.75 SPS, (bits 12|14|16|18) → void; IllegalArgumentException otherwise
            adc.setGain(8);                                                                         // select PGA x8, (gain 1|2|4|8) → void
            System.out.println(adc.getResolution() + " " + adc.getGain());                          // read back the selection, () → int, int
            System.out.println(adc.lsb() + " " + adc.fullScale());                                  // LSB and full scale, () → double V, double V

            adc.setResolution(12);                                                                  // back to 12 bit / 240 SPS, (bits) → void
            adc.setGain(1);                                                                         // back to x1, (gain) → void
            adc.setChannel(0);                                                                      // channel for continuous conversion, (channel 0–1) → void
            adc.startContinuous();                                                                  // start continuous conversion, () or (channel) → void; the first sample after a change is discarded
            System.out.println(adc.isContinuous());                                                 // report the mode, () → boolean
            System.out.println(adc.readContinuousRaw());                                            // next fresh code, () → int
            System.out.println(adc.readContinuous());                                               // next fresh voltage, () → double V
            System.out.println(adc.readConfig() + " " + adc.isReady());                             // config byte and RDY, () → int, boolean
            adc.stopContinuous();                                                                   // back to one-shot / standby, () → void

            adc.generalCallConvert();                                                               // start a conversion on every device, () → void; sends 0x00 0x08
            Thread.sleep(100);
            System.out.println(adc.readPendingRaw());                                               // result of that conversion, () → int
            adc.generalCallReset();                                                                 // reset to power-on defaults, () → void; sends 0x00 0x06, the driver state follows
        }
    }
}
