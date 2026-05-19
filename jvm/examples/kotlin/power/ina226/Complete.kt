///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Full

fun main() {
    val pi4j = Pi4J.newAutoContext()                                    // initialise Pi4J, () → Context
    try {
        I2CTransport(pi4j, 1, 0x40).use { transport ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CTransport

            val ina = Ina226Full(transport, 0.1, 2.0)                  // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Full

            val v  = ina.voltage()                                      // read bus voltage, () → Double V
                                                                        // unsigned 16-bit, 1.25 mV per LSB
            val vs = ina.shuntVoltage()                                 // read shunt voltage, () → Double V
                                                                        // signed 16-bit, 2.5 µV per LSB
            val c  = ina.current()                                      // read current, () → Double A
                                                                        // signed, Current_LSB = maxCurrent / 32768 per bit
            val p  = ina.power()                                        // read power, () → Double W
                                                                        // unsigned, power = 25 × Current_LSB × raw
            println("V=%.3f V  Vshunt=%.6f V  I=%.4f A  P=%.4f W".format(v, vs, c, p))

            ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,    // configure ADC, (avg=4, vbusCt=4, vshCt=4, mode=7) → Unit
                          Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT)
                                                                        // sets 128-sample averaging and 1.1 ms conversion times; continuous mode

            val ready = ina.conversionReady()                           // check conversion ready flag, () → Boolean
                                                                        // reads CVRF bit (bit 3) from Mask/Enable register
            println("Conversion ready: $ready")

            val ovf = ina.overflow()                                    // check math overflow flag, () → Boolean
                                                                        // reads OVF bit (bit 2) from Mask/Enable register
            println("Overflow: $ovf")

            ina.setAlert(Ina226Full.POL, 1.0)                          // set power over-limit alert to 1 W, (function=POL, limit=1.0 W) → Unit
                                                                        // writes function to Mask/Enable, limit raw = int(1.0 / (25 × Current_LSB)) to Alert Limit

            val flags = ina.alertFlags()                                // read alert flags, () → Int
                                                                        // reads Mask/Enable register; reading also clears the alert latch
            println("Alert flags: 0x%04X".format(flags))

            val mfrId = ina.manufacturerId()                            // read manufacturer ID, () → Int
                                                                        // should return 0x5449 ("TI")
            val dieId = ina.dieId()                                     // read die ID, () → Int
                                                                        // should return 0x2260 for INA226
            println("Manufacturer ID: 0x%04X  Die ID: 0x%04X".format(mfrId, dieId))

            ina.shutdown()                                              // enter power-down mode, () → Unit
                                                                        // sets MODE=000; previously active mode stored for wake()
            Thread.sleep(100)

            ina.wake()                                                  // restore previous operating mode, () → Unit
                                                                        // writes previously stored mode bits back to Configuration register
            Thread.sleep(10)

            ina.reset()                                                 // reset chip and re-write calibration, () → Unit
                                                                        // sets RST bit; chip returns to 0x4127; calibration re-written
        }
    } finally {
        pi4j.shutdown()
    }
}
