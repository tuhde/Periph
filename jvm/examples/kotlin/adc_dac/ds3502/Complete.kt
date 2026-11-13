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
        pot.setWiper(100)                                                                          // Set wiper position, (code 0–127) → void
        // writes WR only; the EEPROM is not touched
        println(pot.getWiper())                                                                    // Read wiper position, () → int 0–127
        // reads WR; always the live wiper, never the IVR
        pot.setVoltage(6.0)                                                                        // Set RW output voltage, (volts V) → void
        // nearest code of V = V_RL + (V_RH − V_RL)·code/127
        println(pot.getVoltage())                                                                  // Read unloaded RW voltage, () → double V
        pot.setRatio(0.25)                                                                         // Set divider ratio, (ratio 0.0–1.0) → void
        // code = round(ratio × 127)
        println(pot.getRatio())                                                                    // Read divider ratio, () → double
        println(pot.getResistance('L'))                                                            // Read wiper-to-RL resistance, (terminal='L') → double Ω
        // nominal, RTOTAL = 10 kΩ ±20 %
        println(pot.getResistance('H'))                                                            // Read wiper-to-RH resistance, (terminal='L') → double Ω
        println(pot.increment(5))                                                                  // Step the wiper, (steps) → int 0–127
        // read, add, clamp, write; returns the new code
        println(pot.getMode())                                                                     // Read MODE, () → boolean
        // true if writes to 0x00 also program the EEPROM
        pot.setMode(false)                                                                         // Select SRAM-only writes, (eeprom boolean) → void
        // CR = 0x80; true would write CR = 0x00
        pot.storeWiper(64)                                                                         // Store power-up position, (value) → void
        // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
        pot.waitReady()                                                                            // Acknowledge polling, (timeoutMs=30) → void
        // throws IOException if the chip keeps NACKing
        pot.setEndpoints(12.0, 2.0)                                                                // Change RH / RL voltages, (vHigh V, vLow V) → void
        // affects the conversions only; no bus traffic
    }
}
