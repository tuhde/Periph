///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.DS3502Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, DS3502Full.DEFAULT_ADDRESS)                                // open I²C bus, device address, (bus, address=0x28) → I2CConnection
try {
    def pot = new DS3502Full(connection, 15.0d, 0.0d)                                              // construct driver, (connection, vHigh=1.0 V, vLow=0.0 V) → DS3502Full
    pot.setWiper(100)                                                                              // Set wiper position, (code 0–127) → void
    // writes WR only; the EEPROM is not touched
    println(pot.getWiper())                                                                        // Read wiper position, () → int 0–127
    // reads WR; always the live wiper, never the IVR
    pot.setVoltage(6.0d)                                                                           // Set RW output voltage, (volts V) → void
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    println(pot.getVoltage())                                                                      // Read unloaded RW voltage, () → double V
    pot.setRatio(0.25d)                                                                            // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 127)
    println(pot.getRatio())                                                                        // Read divider ratio, () → double
    println(pot.getResistance('L'))                                                                // Read wiper-to-RL resistance, (terminal='L') → double Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    println(pot.getResistance('H'))                                                                // Read wiper-to-RH resistance, (terminal='L') → double Ω
    println(pot.increment(5))                                                                      // Step the wiper, (steps) → int 0–127
    // read, add, clamp, write; returns the new code
    println(pot.getMode())                                                                         // Read MODE, () → boolean
    // true if writes to 0x00 also program the EEPROM
    pot.setMode(false)                                                                             // Select SRAM-only writes, (eeprom boolean) → void
    // CR = 0x80; true would write CR = 0x00
    pot.storeWiper(64)                                                                             // Store power-up position, (value) → void
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    pot.waitReady()                                                                                // Acknowledge polling, (timeoutMs=30) → void
    // throws IOException if the chip keeps NACKing
    pot.setEndpoints(12.0d, 2.0d)                                                                  // Change RH / RL voltages, (vHigh V, vLow V) → void
    // affects the conversions only; no bus traffic
} finally {
    connection.close()
}
