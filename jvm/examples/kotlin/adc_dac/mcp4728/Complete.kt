///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4728Full

fun main() {
    I2CTransport(1, 0x60).use { transport ->             // open device transport, (bus, address) → I2CTransport
    I2CTransport(1, 0x00).use { gcTransport ->           // open general call transport, (bus, address=0x00) → I2CTransport

        val dac = Mcp4728Full(transport, gcTransport)             // construct driver, (transport, generalCall) → Mcp4728Full

        dac.setVoltage(0, 0.5)                                     // set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → Unit
                                                                    // Multi-Write, V_REF=external, gain=×1, PD=00
        dac.setRaw(2, 3000)                                        // set channel C raw 12-bit code, (channel=0–3, code=0–4095) → Unit
                                                                    // clamps to [0, 4095]; writes channel C only
        dac.setAll(doubleArrayOf(0.1, 0.2, 0.3, 0.4))              // update all four channels simultaneously, (fractions[4]) → Unit
                                                                    // single 8-byte Fast Write transaction; EEPROM unaffected
        dac.setVoltageEeprom(0, 0.5, 0, 1)                         // set channel A and persist to EEPROM, (channel, fraction, vref=0/1, gain=1/2) → Unit
                                                                    // Single Write updates DAC register and nonvolatile EEPROM
        dac.setRawEeprom(1, 2048, 0, 1)                            // set channel B raw code and persist, (channel, code, vref=0/1, gain=1/2) → Unit
                                                                    // Single Write; takes up to 50 ms for EEPROM write
        dac.setAllEeprom(                                          // update all four channels + EEPROM, (fractions[4], vrefs[4], gains[4]) → Unit
            doubleArrayOf(0.0, 0.25, 0.5, 0.75),
            intArrayOf(0, 0, 0, 0),
            intArrayOf(1, 1, 1, 1)
        )                                                            // Sequential Write from A to D; persists all four at the end
        dac.setVref(0, 0, 0, 0)                                    // set V_REF for all four channels, (vref_a, vref_b, vref_c, vref_d) → Unit
                                                                    // 0 = external V_DD; volatile register only
        dac.setGain(1, 1, 1, 1)                                    // set gain for all four channels, (gain_a, gain_b, gain_c, gain_d) → Unit
                                                                    // 1 = ×1, 2 = ×2; volatile register only
        dac.setPowerDown(0, 0, 0, 0)                               // set power-down for all four channels, (pd_a, pd_b, pd_c, pd_d) → Unit
                                                                    // 0 = normal, 1 = 1 kΩ, 2 = 100 kΩ, 3 = 500 kΩ to GND
        Thread.sleep(50)                                           // wait for EEPROM write to complete, (ms) → Unit

        val state = dac.read()                                     // read all four channels' DAC and EEPROM state, () → ReadResult
                                                                    // 4 ChannelState entries with code, vref, gain, power_down, eeprom_*
        println("ch0 code=${state.channel[0].code} eeprom_ready=${state.eepromReady}")

        dac.softwareUpdate()                                       // latch all V_OUT simultaneously, () → Unit
                                                                    // General Call 0x00, 0x08; equivalent to LDAC pin pulse
        dac.wakeUp()                                               // clear all PD bits via General Call Wake-Up, () → Unit
                                                                    // sends 0x00, 0x09; clears power-down on all four channels
        dac.reset()                                                // reload EEPROM into all DAC registers, () → Unit
                                                                    // General Call 0x00, 0x06; triggers internal POR

        val ready = dac.isEepromReady()                            // check if EEPROM write is complete, () → Boolean
                                                                    // true when no EEPROM write is in progress
        println("EEPROM ready: $ready")
    }}
}
