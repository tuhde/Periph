///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Pcf8591Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x48)) {   // open device transport, (bus, address) → I2CTransport

            var adc = new Pcf8591Full(transport);                    // construct driver, (transport) → Pcf8591Full

            int ch0Raw = adc.readChannel(0);                         // read single channel, (channel=0–3) → int
                                                                    // discards the stale first conversion byte; returns 0–255
            int ch1Raw = adc.readChannel(1);                         // read single channel, (channel=0–3) → int
                                                                    // selects channel 1 via the control byte, returns 0–255
            int[] allRaw = adc.readAll();                            // read all four channels, () → int[4]
                                                                    // sets AI=1 and reads 5 bytes; discards stale byte 0

            double v0 = adc.readChannelVoltage(0, 3.3, 0.0);         // read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → double V
                                                                    // converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256
            double[] vAll = adc.readAllVoltage(3.3, 0.0);           // read all channels as voltages, (vref=3.3 V, vagnd=0.0 V) → double[4] V
                                                                    // returns four voltages using the same conversion

            adc.configure(Pcf8591Full.MODE_3_DIFFERENTIAL, false, false);   // configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → void
                                                                            // sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI
            int diff = adc.readDifferential(0);                      // read differential channel, (channel=0–2) → int
                                                                    // returns signed 8-bit two's complement (-128 to 127)
            adc.configure(Pcf8591Full.MODE_4_SINGLE_ENDED, false, true);   // configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → void
                                                                            // restores 4 single-ended mode and enables the DAC output
            adc.setDac(128);                                         // enable DAC and set raw value, (value=0–255) → void
                                                                    // sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2
            adc.setDacVoltage(0.25);                                 // set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → void
                                                                    // maps fraction to 0–255 and writes the DAC; AOUT follows
            adc.disableDac();                                        // disable DAC output, () → void
                                                                    // clears AOE; AOUT returns to high-impedance
            System.out.printf("ch0_raw=%d v0=%.3fV diff=%d%n", ch0Raw, v0, diff);
        }
    }
}
