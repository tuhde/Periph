///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina219Full

fun main() {
    I2CTransport(1, 0x40).use { transport ->                // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
        val ina = Ina219Full(transport, 0.1, 2.0)                  // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina219Full

        ina.configure(                                             // write configuration register, (brng, pga, badc, sadc, mode) → Unit
            Ina219Full.BRNG_32V,                                   // bus range 32 V full-scale
            Ina219Full.PGA_8,                                      // PGA /8: ±320 mV shunt range
            Ina219Full.ADC_12BIT,                                  // bus ADC 12-bit single sample
            Ina219Full.ADC_12BIT,                                  // shunt ADC 12-bit single sample
            Ina219Full.MODE_SHUNT_BUS_CONT)                        // continuous shunt+bus measurement
                                                                  // packs all fields into config reg 0x00 and writes it atomically

        val v  = ina.voltage()                                     // read bus voltage, () → Double V
                                                                  // right-shifts raw register by 3, multiplies by 4 mV LSB
        val vs = ina.shuntVoltage()                               // read shunt voltage, () → Double V
                                                                  // interprets raw register as signed 16-bit, multiplies by 10 µV LSB
        val i  = ina.current()                                    // read current, () → Double A
                                                                  // interprets raw register as signed 16-bit, multiplies by Current_LSB
        val p  = ina.power()                                      // read power, () → Double W
                                                                  // interprets raw register as unsigned 16-bit, multiplies by Power_LSB (= 20 × Current_LSB)
        println("V_bus=%.3f V  V_shunt=%.6f V  I=%.4f A  P=%.4f W".format(v, vs, i, p))

        val ready = ina.conversionReady()                         // read conversion-ready flag, () → Boolean
                                                                  // tests CNVR bit (bit 1) of Bus Voltage register; set when new result available
        val ovf   = ina.overflow()                                // read math overflow flag, () → Boolean
                                                                  // tests OVF bit (bit 0) of Bus Voltage register; set on current/power overflow
        println("conversionReady=$ready  overflow=$ovf")

        // Switch to one-shot triggered mode with 128-sample averaging
        ina.configure(                                             // write configuration register, (brng, pga, badc, sadc, mode) → Unit
            Ina219Full.BRNG_32V,
            Ina219Full.PGA_8,
            Ina219Full.ADC_AVG_128,                                // 128-sample average, 68 ms conversion
            Ina219Full.ADC_AVG_128,
            Ina219Full.MODE_SHUNT_BUS_TRIG)                        // single-shot triggered

        ina.trigger()                                              // trigger a one-shot conversion, () → Unit
                                                                  // re-writes the cached config register; starts a new conversion in triggered mode
        Thread.sleep(150)                                         // wait for 128-sample conversion (~68 ms × 2 channels + margin)

        println("triggered V=%.3f V  I=%.4f A".format(ina.voltage(), ina.current()))

        ina.shutdown()                                             // enter power-down mode, () → Unit
                                                                  // writes config reg with MODE=000 (power-down); ADC powered off
        Thread.sleep(10)

        ina.wake()                                                 // exit power-down, restore configuration, () → Unit
                                                                  // re-writes the full cached config register including original MODE bits
        Thread.sleep(10)

        ina.reset()                                                // software reset and restore config+calibration, () → Unit
                                                                  // sets RST bit (bit 15), then re-writes config and recomputes CAL register
        Thread.sleep(10)

        println("after reset V=%.3f V".format(ina.voltage()))
    }

}
