///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-kotlin:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ina260Full

fun main() {
    I2CConnection(1, 0x40).use { connection ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CConnection

        val ina = Ina260Full(connection)                  // construct driver, (connection) → Ina260Full

        val v  = ina.voltage()                                      // read bus voltage, () → Double V
                                                                    // unsigned 16-bit, 1.25 mV per LSB
        val c  = ina.current()                                      // read current, () → Double A
                                                                    // signed, 1.25 mA per LSB
        val p  = ina.power()                                        // read power, () → Double W
                                                                    // unsigned, 10 mW per LSB
        println("V=%.3f V  I=%.4f A  P=%.4f W".format(v, c, p))

        ina.configure(Ina260Full.AVG_128, Ina260Full.CT_1100US,    // configure ADC, (avg=4, vbusCt=4, ishCt=4, mode=7) → Unit
                      Ina260Full.CT_1100US, Ina260Full.MODE_SHUNT_BUS_CONT)
                                                                    // sets 128-sample averaging and 1.1 ms conversion times; continuous mode

        val ready = ina.conversionReady()                           // check conversion ready flag, () → Boolean
                                                                    // reads CVRF bit (bit 3) from Mask/Enable register
        println("Conversion ready: $ready")

        val ovf = ina.overflow()                                    // check math overflow flag, () → Boolean
                                                                    // reads OVF bit (bit 2) from Mask/Enable register
        println("Overflow: $ovf")

        ina.setAlert(Ina260Full.POL, 1.0)                          // set power over-limit alert to 1 W, (function=POL, limit=1.0 W) → Unit
        ina.clearAlert()                                          // disable all alerts, () → Unit
        ina.trigger()                                             // one triggered shunt+bus conversion, () → Unit
                                                                    // writes function to Mask/Enable, limit raw = int(1.0 / (25 × Current_LSB)) to Alert Limit

        val flags = ina.alertFlags()                                // read alert flags, () → Int
                                                                    // reads Mask/Enable register; reading also clears the alert latch
        println("Alert flags: 0x%04X".format(flags))

        val mfrId = ina.manufacturerId()                            // read manufacturer ID, () → Int
                                                                    // should return 0x5449 ("TI")
        val dieId = ina.dieId()                                     // read die ID, () → Int
                                                                    // (value >> 4) == 0x227 for INA260
        println("Manufacturer ID: 0x%04X  Die ID: 0x%04X".format(mfrId, dieId))

        ina.shutdown()                                              // enter power-down mode, () → Unit
                                                                    // sets MODE=000; previously active mode stored for wake()
        Thread.sleep(100)

        ina.wake()                                                  // restore previous operating mode, () → Unit
                                                                    // writes previously stored mode bits back to Configuration register
        Thread.sleep(10)

        ina.reset()                                                 // reset chip to power-on defaults, () → Unit
                                                                    // sets RST bit; chip returns to 0x6127
    }

}
