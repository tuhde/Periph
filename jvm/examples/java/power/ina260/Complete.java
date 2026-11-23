///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ina260Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CConnection

            var ina = new Ina260Full(connection);                 // construct driver, (connection) → Ina260Full

            double v  = ina.voltage();                                      // read bus voltage, () → double V
                                                                            // unsigned 16-bit, 1.25 mV per LSB
            double c  = ina.current();                                      // read current, () → double A
                                                                            // signed, 1.25 mA per LSB
            double p  = ina.power();                                        // read power, () → double W
                                                                            // unsigned, 10 mW per LSB
            System.out.printf("V=%.3f V  I=%.4f A  P=%.4f W%n", v, c, p);

            ina.configure(Ina260Full.AVG_128, Ina260Full.CT_1100US,        // configure ADC, (avg=4, vbusCt=4, ishCt=4, mode=7) → void
                          Ina260Full.CT_1100US, Ina260Full.MODE_SHUNT_BUS_CONT);
                                                                            // sets 128-sample averaging and 1.1 ms conversion times; continuous mode

            boolean ready = ina.conversionReady();                         // check conversion ready flag, () → boolean
                                                                            // reads CVRF bit (bit 3) from Mask/Enable register
            System.out.println("Conversion ready: " + ready);

            boolean ovf = ina.overflow();                                   // check math overflow flag, () → boolean
                                                                            // reads OVF bit (bit 2) from Mask/Enable register
            System.out.println("Overflow: " + ovf);

            ina.setAlert(Ina260Full.POL, 1.0);                             // set power over-limit alert to 1 W, (function=POL, limit=1.0 W) → void
            ina.clearAlert();                                                   // disable all alerts, () → void
            ina.trigger();                                                      // one triggered shunt+bus conversion, () → void
                                                                            // writes function to Mask/Enable, limit raw = int(1.0 / (25 × Current_LSB)) to Alert Limit

            int flags = ina.alertFlags();                                   // read alert flags, () → int
                                                                            // reads Mask/Enable register; reading also clears the alert latch
            System.out.printf("Alert flags: 0x%04X%n", flags);

            int mfrId = ina.manufacturerId();                               // read manufacturer ID, () → int
                                                                            // should return 0x5449 ("TI")
            int dieId = ina.dieId();                                        // read die ID, () → int
                                                                            // (value >> 4) == 0x227 for INA260
            System.out.printf("Manufacturer ID: 0x%04X  Die ID: 0x%04X%n", mfrId, dieId);

            ina.shutdown();                                                 // enter power-down mode, () → void
                                                                            // sets MODE=000; previously active mode stored for wake()
            Thread.sleep(100);

            ina.wake();                                                     // restore previous operating mode, () → void
                                                                            // writes previously stored mode bits back to Configuration register
            Thread.sleep(10);

            ina.reset();                                                    // reset chip to power-on defaults, () → void
                                                                            // sets RST bit; chip returns to 0x6127

        }
    }
}
