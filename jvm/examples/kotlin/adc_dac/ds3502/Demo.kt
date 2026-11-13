///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS3502Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DS3502Full.DEFAULT_ADDRESS).use { connection ->                             // open I²C bus, device address, (bus, address=0x28) → I2CConnection
        val pot = DS3502Full(connection, 15.0, 0.0)                                                // construct driver, (connection, vHigh=1.0 V, vLow=0.0 V) → DS3502Full
        // --- Power-up position ---
        // The constructor leaves the wiper where the IVR put it (factory default 0x40).
        println("power-up wiper ${pot.getWiper()} -> ${pot.getVoltage()} V")                       // Read wiper position, () → int 0–127

        // --- Set 6.0 V ---
        pot.setVoltage(6.0)                                                                        // Set RW output voltage, (volts V) → void
        println("code ${pot.getWiper()} ratio ${pot.getRatio()} voltage ${pot.getVoltage()} V")    // Read divider ratio, () → double

        // --- Sweep in 16 steps ---
        // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
        for (code in 0..127 step 8) {
            pot.setWiper(code)                                                                     // Set wiper position, (code 0–127) → void
            println("code ${code} -> ${pot.getVoltage()} V")                                       // Read unloaded RW voltage, () → double V
            Thread.sleep(200)
        }

        // --- Step up and down ---
        pot.setVoltage(6.0)
        println("+4 -> ${pot.increment(4)}")                                                       // Step the wiper, (steps) → int 0–127
        println("-4 -> ${pot.increment(-4)}")

        // --- MODE ---
        // MODE = 1 (the working mode) keeps wiper writes in SRAM; storeWiper switches
        // to MODE = 0 only for the duration of the EEPROM write.
        println("eeprom mode ${pot.getMode()}")                                                    // Read MODE, () → boolean
        pot.setMode(false)                                                                         // Select SRAM-only writes, (eeprom boolean) → void

        // --- Persist the chosen code ---
        // This consumes one of the EEPROM's limited write cycles.
        val chosen = pot.getWiper()
        pot.storeWiper(chosen)                                                                     // Store power-up position, (value) → void
        pot.waitReady()                                                                            // Acknowledge polling, (timeoutMs=30) → void
        println("read back ${pot.getWiper()}")
    }
}
