///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4725Full

fun main() {
    I2CTransport(1, 0x60).use { transport ->             // open device transport, (bus, address) → I2CTransport
    I2CTransport(1, 0x00).use { gcTransport ->           // open general call transport, (bus, address=0x00) → I2CTransport

        val dac = Mcp4725Full(transport, gcTransport)           // construct driver, (transport, generalCall) → Mcp4725Full

        dac.setVoltage(0.5)                                     // set output to 50% of VDD, (fraction=0.0–1.0) → Unit
                                                                // converts fraction to 12-bit code and issues a Fast Write
        dac.setRaw(2048)                                        // set output to raw code, (code=0–4095) → Unit
                                                                // maps directly to DAC register; 2048 ≈ 50% of VDD
        dac.setVoltageEeprom(0.75)                              // set output and persist to EEPROM, (fraction=0.0–1.0) → Unit
                                                                // DAC register and EEPROM are both written; output survives power cycle
        dac.setRawEeprom(3072)                                  // set raw code and persist to EEPROM, (code=0–4095) → Unit
                                                                // issues a Write DAC + EEPROM command; takes up to 50 ms

        Thread.sleep(50)                                        // wait for EEPROM write to complete, (ms) → Unit

        val state = dac.read()                                  // read device state, () → ReadResult
                                                                // returns DAC register, EEPROM contents, power-down mode, and RDY/BSY flag
        println("code=${state.code} fraction=%.4f pd=${state.powerDown} eepromCode=${state.eepromCode} eepromPd=${state.eepromPowerDown} ready=${state.eepromReady}"
            .format(state.voltageFraction))

        dac.setPowerDown(1)                                     // enter power-down mode, (mode=0–3) → Unit
                                                                // 1 = 1 kΩ pull-down to GND; output pin goes high-Z
        Thread.sleep(100)

        dac.wakeUp()                                            // send General Call Wake-Up to all MCP47xx on bus, () → Unit
                                                                // clears power-down bits in the DAC register; output resumes
        dac.reset()                                             // send General Call Reset to all MCP47xx on bus, () → Unit
                                                                // triggers internal POR; reloads EEPROM into DAC register

        val ready = dac.isEepromReady()                        // read RDY/BSY bit, () → Boolean
                                                                // true when no EEPROM write is in progress
        println("EEPROM ready: $ready")
    }}

}
