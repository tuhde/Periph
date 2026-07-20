///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.gas.Ens160Full

fun main() {
    I2CTransport(1, 0x52).use { transport ->                 // open I²C bus 1, device 0x52, (bus, address=0x52) → I2CTransport
        val sensor = Ens160Full(transport)                          // construct driver, verifies PART_ID and starts STANDARD mode, (transport) → Ens160Full

        val fw = sensor.getFirmwareVersion()                        // get firmware version, () → IntArray {major, minor, release}
                                                                     // switches to IDLE, issues GET_APPVER, returns to STANDARD
        println("Firmware: ${fw[0]}.${fw[1]}.${fw[2]}")

        sensor.setCompensation(25.0, 50.0)                          // set compensation, (tempCelsius, rhPercent) → Unit
                                                                     // improves accuracy with external T/RH readings

        sensor.configureInterrupt(true, false, false, true, false)  // configure interrupt, (enabled, activeHigh, pushPull, onData, onGpr) → Unit
                                                                     // sets INTn pin behavior for new data notification

        println("Waiting for warm-up...")
        while (true) {                                              // Wait for valid data, () → blocks until warm
            try { sensor.readAirQuality(); break } catch (e: Exception) { Thread.sleep(1000) }
        }

        val tvoc = sensor.readTvoc()                                // read TVOC, () → Double ppb
        val eco2 = sensor.readEco2()                                // read eCO2, () → Double ppm
        val aqi = sensor.readAqi()                                  // read AQI, () → Int 1–5
        val ethanol = sensor.readEthanol()                          // read ethanol, () → Double ppb
                                                                     // alias of DATA_TVOC at 0x22
        val r1 = sensor.readRawResistance(1)                        // read raw resistance, (sensor=1 or 4) → Double Ohms
        val r4 = sensor.readRawResistance(4)                        // read raw resistance, (sensor=1 or 4) → Double Ohms
        val actuals = sensor.readCompensationActuals()              // read compensation actuals, () → DoubleArray {tempCelsius, rhPercent}
                                                                     // returns T/RH values used by sensor

        println("TVOC=${tvoc.toInt()} ppb, eCO2=${eco2.toInt()} ppm, AQI=$aqi")
        println("Ethanol=${ethanol.toInt()} ppb, R1=${r1.toInt()} Ohm, R4=${r4.toInt()} Ohm")
        println("Actual T=%.1f C, RH=%.1f %%".format(actuals[0], actuals[1]))

        sensor.sleep()                                              // enter deep sleep, () → Unit
                                                                     // reduces current to ~10 uA
        Thread.sleep(1000)
        sensor.wake()                                               // wake and resume sensing, () → Unit
                                                                     // transitions IDLE then STANDARD
    }
}
