///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina226Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                                   // initialise Pi4J, () → Context
        try (var transport = new I2CTransport(pi4j, 1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport

            var ina = new Ina226Full(transport, 0.1, 2.0);                 // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Full

            double v  = ina.voltage();                                      // read bus voltage, () → double V
                                                                            // unsigned 16-bit, 1.25 mV per LSB
            double vs = ina.shuntVoltage();                                 // read shunt voltage, () → double V
                                                                            // signed 16-bit, 2.5 µV per LSB
            double c  = ina.current();                                      // read current, () → double A
                                                                            // signed, Current_LSB = maxCurrent / 32768 per bit
            double p  = ina.power();                                        // read power, () → double W
                                                                            // unsigned, power = 25 × Current_LSB × raw
            System.out.printf("V=%.3f V  Vshunt=%.6f V  I=%.4f A  P=%.4f W%n", v, vs, c, p);

            ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,        // configure ADC, (avg=4, vbusCt=4, vshCt=4, mode=7) → void
                          Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT);
                                                                            // sets 128-sample averaging and 1.1 ms conversion times; continuous mode

            boolean ready = ina.conversionReady();                         // check conversion ready flag, () → boolean
                                                                            // reads CVRF bit (bit 3) from Mask/Enable register
            System.out.println("Conversion ready: " + ready);

            boolean ovf = ina.overflow();                                   // check math overflow flag, () → boolean
                                                                            // reads OVF bit (bit 2) from Mask/Enable register
            System.out.println("Overflow: " + ovf);

            ina.setAlert(Ina226Full.POL, 1.0);                             // set power over-limit alert to 1 W, (function=POL, limit=1.0 W) → void
                                                                            // writes function to Mask/Enable, limit raw = int(1.0 / (25 × Current_LSB)) to Alert Limit

            int flags = ina.alertFlags();                                   // read alert flags, () → int
                                                                            // reads Mask/Enable register; reading also clears the alert latch
            System.out.printf("Alert flags: 0x%04X%n", flags);

            int mfrId = ina.manufacturerId();                               // read manufacturer ID, () → int
                                                                            // should return 0x5449 ("TI")
            int dieId = ina.dieId();                                        // read die ID, () → int
                                                                            // should return 0x2260 for INA226
            System.out.printf("Manufacturer ID: 0x%04X  Die ID: 0x%04X%n", mfrId, dieId);

            ina.shutdown();                                                 // enter power-down mode, () → void
                                                                            // sets MODE=000; previously active mode stored for wake()
            Thread.sleep(100);

            ina.wake();                                                     // restore previous operating mode, () → void
                                                                            // writes previously stored mode bits back to Configuration register
            Thread.sleep(10);

            ina.reset();                                                    // reset chip and re-write calibration, () → void
                                                                            // sets RST bit; chip returns to 0x4127; calibration re-written

        } finally {
            pi4j.shutdown();
        }
    }
}
