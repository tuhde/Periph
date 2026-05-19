///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina3221Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                                    // initialise Pi4J, () → Context
        try (var transport = new I2CTransport(pi4j, 1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            var ina = new Ina3221Full(transport);                           // construct driver (0.1 Ω shunt all channels), (transport) → Ina3221Full

            ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT);       // configure averaging and conversion times, (avg=0–7, vbusCt=0–7, vshCt=0–7, mode=0–7) → void
                                                                            // avg=1 means 4-sample average; vbusCt/vshCt=4 means 1.1 ms each

            for (int ch = 1; ch <= 3; ch++) {
                ina.enableChannel(ch, true);                                // enable a channel, (channel=1–3, enabled) → void
                                                                            // sets the CH<n>en bit in the configuration register
                boolean en = ina.channelEnabled(ch);                        // read channel enable state, (channel=1–3) → boolean
                                                                            // true when the CH<n>en bit is set
                System.out.printf("CH%d enabled: %b%n", ch, en);
            }

            for (int ch = 1; ch <= 3; ch++) {
                double v  = ina.voltage(ch);                                // read bus voltage, (channel=1–3) → double V
                                                                            // left-aligned 12-bit unsigned, 8 mV LSB
                double sv = ina.shuntVoltage(ch);                          // read shunt voltage, (channel=1–3) → double V
                                                                            // left-aligned 13-bit signed, 40 µV LSB
                double i  = ina.current(ch);                               // compute current from shunt voltage, (channel=1–3) → double A
                                                                            // current = shuntVoltage / rShunt
                double p  = ina.power(ch);                                 // compute power, (channel=1–3) → double W
                                                                            // power = busVoltage × current
                System.out.printf("CH%d: %.3f V  %.6f V_shunt  %.4f A  %.4f W%n", ch, v, sv, i, p);
            }

            boolean cvrf = ina.conversionReady();                          // read conversion-ready flag, () → boolean
                                                                            // CVRF bit (bit 0) of Mask/Enable register; set after each full conversion
            System.out.println("Conversion ready: " + cvrf);

            ina.setCriticalAlert(1, 0.05);                                 // set critical alert on channel 1, (channel=1–3, limitV) → void
                                                                            // alert fires when |shunt voltage| exceeds this threshold
            ina.setWarningAlert(1, 0.04);                                  // set warning alert on channel 1, (channel=1–3, limitV) → void
                                                                            // alert fires when |shunt voltage| exceeds this threshold
            ina.setCriticalAlert(2, 0.05);                                 // set critical alert on channel 2, (channel=1–3, limitV) → void
            ina.setWarningAlert(2, 0.04);                                  // set warning alert on channel 2, (channel=1–3, limitV) → void
            ina.setCriticalAlert(3, 0.05);                                 // set critical alert on channel 3, (channel=1–3, limitV) → void
            ina.setWarningAlert(3, 0.04);                                  // set warning alert on channel 3, (channel=1–3, limitV) → void

            ina.setPowerValidLimits(5.5, 4.5);                             // set power-valid thresholds, (upperV, lowerV) → void
                                                                            // PVF bit set when all enabled bus voltages are within [lowerV, upperV]
            boolean pv = ina.powerValid();                                  // read power-valid flag, () → boolean
                                                                            // PVF bit (bit 2) of Mask/Enable register
            System.out.println("Power valid: " + pv);

            ina.setSummationChannels(new int[]{1, 2, 3}, 0.1);            // enable summation for all channels with limit, (channels, limitV) → void
                                                                            // sets SCC bits in Mask/Enable and writes the sum limit register
            double sumV = ina.summationValue();                             // read shunt-voltage sum, () → double V
                                                                            // 14-bit signed, left-aligned by 1; LSB = 20 µV
            System.out.printf("Shunt voltage sum: %.6f V%n", sumV);

            int flags = ina.alertFlags();                                   // read and clear alert flags, () → int
                                                                            // reads Mask/Enable register; clears latched flags; inspect against CF1/WF1/PVF etc.
            System.out.printf("Alert flags: 0x%04X%n", flags);

            int mfr = ina.manufacturerId();                                 // read manufacturer ID, () → int
                                                                            // expected 0x5449 ("TI")
            int die = ina.dieId();                                          // read die ID, () → int
                                                                            // expected 0x3220
            System.out.printf("Manufacturer ID: 0x%04X  Die ID: 0x%04X%n", mfr, die);

            ina.shutdown();                                                  // enter power-down mode (MODE=000), () → void
                                                                            // saves current MODE so wake() can restore it
            Thread.sleep(100);

            ina.wake();                                                      // restore saved operating mode, () → void
                                                                            // writes previously saved MODE bits back into config register
            Thread.sleep(100);

            ina.reset();                                                     // software reset via RST bit, then restore saved mode, () → void
                                                                            // resets all registers to hardware defaults, then writes saved MODE back

        } finally {
            pi4j.shutdown();
        }
    }
}
