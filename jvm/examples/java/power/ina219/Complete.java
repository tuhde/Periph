///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina219Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x40)) {      // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            var ina = new Ina219Full(transport, 0.1, 2.0);            // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina219Full

            ina.configure(                                             // write configuration register, (brng, pga, badc, sadc, mode) → void
                    Ina219Full.BRNG_32V,                              // bus range 32 V full-scale
                    Ina219Full.PGA_8,                                  // PGA /8: ±320 mV shunt range
                    Ina219Full.ADC_12BIT,                              // bus ADC 12-bit single sample
                    Ina219Full.ADC_12BIT,                              // shunt ADC 12-bit single sample
                    Ina219Full.MODE_SHUNT_BUS_CONT);                  // continuous shunt+bus measurement
                                                                      // packs all fields into config reg 0x00 and writes it atomically

            double v = ina.voltage();                                  // read bus voltage, () → double V
                                                                      // right-shifts raw register by 3, multiplies by 4 mV LSB
            double vs = ina.shuntVoltage();                           // read shunt voltage, () → double V
                                                                      // interprets raw register as signed 16-bit, multiplies by 10 µV LSB
            double i  = ina.current();                                // read current, () → double A
                                                                      // interprets raw register as signed 16-bit, multiplies by Current_LSB
            double p  = ina.power();                                  // read power, () → double W
                                                                      // interprets raw register as unsigned 16-bit, multiplies by Power_LSB (= 20 × Current_LSB)
            System.out.printf("V_bus=%.3f V  V_shunt=%.6f V  I=%.4f A  P=%.4f W%n", v, vs, i, p);

            boolean ready = ina.conversionReady();                    // read conversion-ready flag, () → boolean
                                                                      // tests CNVR bit (bit 1) of Bus Voltage register; set when new result available
            boolean ovf   = ina.overflow();                           // read math overflow flag, () → boolean
                                                                      // tests OVF bit (bit 0) of Bus Voltage register; set on current/power overflow
            System.out.println("conversionReady=" + ready + "  overflow=" + ovf);

            // Switch to one-shot triggered mode with 128-sample averaging
            ina.configure(                                             // write configuration register, (brng, pga, badc, sadc, mode) → void
                    Ina219Full.BRNG_32V,
                    Ina219Full.PGA_8,
                    Ina219Full.ADC_AVG_128,                            // 128-sample average, 68 ms conversion
                    Ina219Full.ADC_AVG_128,
                    Ina219Full.MODE_SHUNT_BUS_TRIG);                  // single-shot triggered

            ina.trigger();                                             // trigger a one-shot conversion, () → void
                                                                      // re-writes the cached config register; starts a new conversion in triggered mode
            Thread.sleep(150);                                        // wait for 128-sample conversion (~68 ms × 2 channels + margin)

            System.out.printf("triggered V=%.3f V  I=%.4f A%n", ina.voltage(), ina.current());

            ina.shutdown();                                            // enter power-down mode, () → void
                                                                      // writes config reg with MODE=000 (power-down); ADC powered off
            Thread.sleep(10);

            ina.wake();                                                // exit power-down, restore configuration, () → void
                                                                      // re-writes the full cached config register including original MODE bits
            Thread.sleep(10);

            ina.reset();                                               // software reset and restore config+calibration, () → void
                                                                      // sets RST bit (bit 15), then re-writes config and recomputes CAL register
            Thread.sleep(10);

            System.out.printf("after reset V=%.3f V%n", ina.voltage());

        }
    }
}
