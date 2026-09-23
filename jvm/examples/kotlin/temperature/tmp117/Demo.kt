///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP117Full
import it.uhde.periph.chips.temperature.TMP117Minimal
import java.util.concurrent.LinkedBlockingQueue

/**
 * PT100-replacement cold-chain container thermometer: maximum averaging gives
 * the lowest-noise reading, and the ALERT output fires when the cargo leaves
 * the -25 °C to 8 °C safe transport range; each event is reported with the
 * boundary that tripped. Set REFERENCE_C to a reference thermometer reading to
 * calibrate once and persist the offset to EEPROM.
 */
const val MAX_ALERTS = 10

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1
    val reference = System.getenv("REFERENCE_C")?.toDouble()

    I2CConnection(bus, TMP117Minimal.DEFAULT_ADDRESS).use { connection ->                    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val sensor = TMP117Full(connection)                                               // construct driver and check identity, (connection) → TMP117Full

        // --- Lowest-noise continuous conversion ---
        // 64-conversion averaging with a 1 s cycle gives the quietest result the
        // chip can deliver — a cold-chain log needs stability, not speed.
        sensor.configure(TMP117Full.Mode.CONTINUOUS, 64, 1.0)                             // Configure conversion, (mode=CONTINUOUS, averaging=8, cycleSeconds=1.0 s) → Unit

        // --- One-time calibration against a reference thermometer ---
        // The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
        // the correction survives power cycles. EEPROM endurance is limited — this
        // is a once-per-deployment step, not a loop.
        if (reference != null) {
            Thread.sleep(1100)
            val offset = reference - sensor.readTemperature() +                           // Read temperature, () → double °C
                sensor.getTemperatureOffset()                                             // Read calibration offset, () → double °C
            sensor.unlockEeprom()                                                         // Unlock EEPROM, () → Unit
            sensor.setTemperatureOffset(offset)                                           // Set calibration offset, (celsius °C) → Unit
            while (sensor.isEepromBusy()) {                                               // Check EEPROM busy, () → Boolean
                Thread.sleep(1)
            }
            sensor.lockEeprom()                                                           // Lock EEPROM, () → Unit
            println("calibrated, offset %.4f °C".format(offset))
        }

        // --- Program the safe transport range ---
        // Alert mode flags either side of the window independently; ALERT is
        // active-low open-drain, pulled up on the board.
        sensor.setHighLimit(8.0)                                                          // Set THIGH_LIMIT, (celsius °C) → Unit
        sensor.setLowLimit(-25.0)                                                         // Set TLOW_LIMIT, (celsius °C) → Unit
        sensor.configureAlert(TMP117Full.AlertMode.ALERT, TMP117Full.AlertPolarity.ACTIVE_LOW)  // Configure ALERT, (mode=ALERT, polarity=ACTIVE_LOW, pinFunction=ALERT) → Unit

        // --- Report which boundary tripped ---
        // The status mask comes from CONFIGURATION's alert flags; reading them
        // clears them in Alert mode, re-arming the pin for the next excursion.
        val events = LinkedBlockingQueue<Int>()
        sensor.onInterrupt({ if (it != 0) events.add(it) })                               // Subscribe to ALERT, (callback) → Unit
        println("monitoring, %.2f °C now".format(sensor.readTemperature()))               // Read temperature, () → double °C
        repeat(MAX_ALERTS) {
            val status = events.take()
            val t = sensor.readTemperature()                                              // Read temperature, () → double °C
            val label = when {
                status and TMP117Full.SOURCE_HIGH != 0 -> "too warm - cargo above 8 °C"
                else -> "too cold - cargo below -25 °C"
            }
            println("%.2f °C  %s".format(t, label))
        }

        // --- Stop monitoring after the demo run ---
        sensor.offInterrupt()                                                             // Unsubscribe, () → Unit
    }
}
