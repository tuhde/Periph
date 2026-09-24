///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP117Full
import it.uhde.periph.chips.temperature.TMP117Minimal

// Exercises every method in the TMP117 Full API. EEPROM unlock/lock is shown
// without any write in between, so no power-on default is changed.
fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TMP117Minimal.DEFAULT_ADDRESS).use { connection ->                    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val sensor = TMP117Full(connection)                                               // construct driver and check identity, (connection) → TMP117Full
                                                                                          // checks DEVICE_ID bits 11:0 == 0x117

        println("temperature %.4f °C".format(sensor.readTemperature()))                   // Read temperature, () → double °C
                                                                                          // decodes TEMP_RESULT, 0.0078125 °C two's complement

        sensor.configure(TMP117Full.Mode.CONTINUOUS, 32, 0.5)                             // Configure conversion, (mode=CONTINUOUS, averaging=8, cycleSeconds=1.0 s) → Unit
                                                                                          // writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
        println("config ${sensor.getConfig()}")                                           // Read conversion config, () → Config(mode, averaging, cycleSeconds s)
                                                                                          // decodes MOD, AVG and CONV from CONFIGURATION

        sensor.configure(TMP117Full.Mode.SHUTDOWN)                                        // Configure conversion, (mode=CONTINUOUS, averaging=8, cycleSeconds=1.0 s) → Unit
                                                                                          // MOD=01 stops conversions; TEMP_RESULT keeps its last value
        println("shutdown ${sensor.isShutdown()}")                                        // Check Shutdown mode, () → Boolean
                                                                                          // reads MOD[1:0] == 01
        sensor.triggerOneShot()                                                           // Start one conversion, () → Unit
                                                                                          // MOD=11; returns to Shutdown when done
        while (!sensor.isDataReady()) {                                                   // Check for a fresh result, () → Boolean
            Thread.sleep(10)                                                              // reading Data_Ready clears it
        }
        println("one-shot %.4f °C".format(sensor.readTemperature()))                      // Read temperature, () → double °C
                                                                                          // the one-shot result
        sensor.configure()                                                                // Configure conversion, (mode=CONTINUOUS, averaging=8, cycleSeconds=1.0 s) → Unit
                                                                                          // back to the POR default

        sensor.setHighLimit(30.0)                                                         // Set THIGH_LIMIT, (celsius °C) → Unit
                                                                                          // rounded to the nearest 0.0078125 °C step
        sensor.setLowLimit(10.0)                                                          // Set TLOW_LIMIT, (celsius °C) → Unit
                                                                                          // rounded to the nearest 0.0078125 °C step
        println("high ${sensor.getHighLimit()}")                                          // Read THIGH_LIMIT, () → double °C
                                                                                          // same format as TEMP_RESULT
        println("low ${sensor.getLowLimit()}")                                            // Read TLOW_LIMIT, () → double °C
                                                                                          // same format as TEMP_RESULT

        sensor.setTemperatureOffset(0.25)                                                 // Set calibration offset, (celsius °C) → Unit
                                                                                          // added to every result after linearization
        println("offset ${sensor.getTemperatureOffset()}")                                // Read calibration offset, () → double °C
                                                                                          // decodes TEMP_OFFSET
        sensor.setTemperatureOffset(0.0)                                                  // Set calibration offset, (celsius °C) → Unit
                                                                                          // remove the offset again

        sensor.unlockEeprom()                                                             // Unlock EEPROM, () → Unit
                                                                                          // EUN=1: EEPROM-backed writes now persist
        println("eeprom busy ${sensor.isEepromBusy()}")                                   // Check EEPROM busy, () → Boolean
                                                                                          // reads EEPROM_UL.EEPROM_Busy
        sensor.lockEeprom()                                                               // Lock EEPROM, () → Unit
                                                                                          // EUN=0: writes are volatile again
        println("eeprom1 0x%04X".format(sensor.readEepromScratch(1)))                     // Read EEPROM scratch, (slot 1|2|3) → Int
                                                                                          // slot 1 holds part of the factory unique ID
        sensor.writeEepromScratch(2, 0x1234)                                              // Write EEPROM scratch, (slot 2, value 16-bit) → Unit
                                                                                          // only EEPROM2 is writable; volatile while locked
        println("eeprom2 0x%04X".format(sensor.readEepromScratch(2)))                     // Read EEPROM scratch, (slot 1|2|3) → Int
                                                                                          // reads back EEPROM2

        sensor.configureAlert(TMP117Full.AlertMode.ALERT, TMP117Full.AlertPolarity.ACTIVE_LOW,
            TMP117Full.AlertPinFunction.ALERT)                                            // Configure ALERT, (mode=ALERT, polarity=ACTIVE_LOW, pinFunction=ALERT) → Unit
                                                                                          // sets T/nA, POL and DR/Alert together

        val status = sensor.pollInterrupt()                                               // Read alert flags, () → Int mask
                                                                                          // HIGH_Alert/LOW_Alert; the read clears them in Alert mode
        println("above high ${status and TMP117Full.SOURCE_HIGH != 0} below low ${status and TMP117Full.SOURCE_LOW != 0}")

        sensor.onInterrupt({ println("alert, status mask $it") })                         // Subscribe to ALERT, (callback) → Unit
                                                                                          // callback receives the pollInterrupt() mask
        Thread.sleep(5000)
        sensor.offInterrupt()                                                             // Unsubscribe, () → Unit
                                                                                          // detaches the pin handler or stops the polling thread

        sensor.reset()                                                                    // Software reset, () → Unit
                                                                                          // reloads CONFIGURATION/limits/offset from EEPROM, 2 ms
    }
}
