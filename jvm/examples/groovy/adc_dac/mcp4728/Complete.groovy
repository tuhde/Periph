///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4728Full

def transport   = new I2CTransport(1, 0x60)              // open device transport, (bus, address) → I2CTransport
def gcTransport = new I2CTransport(1, 0x00)              // open general call transport, (bus, address=0x00) → I2CTransport
try {
    def dac = new Mcp4728Full(transport, gcTransport)            // construct driver, (transport, generalCall) → Mcp4728Full

    dac.setVoltage(0, 0.5)                                         // set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → void
                                                                    // Multi-Write, V_REF=external, gain=×1, PD=00
    dac.setRaw(2, 3000)                                            // set channel C raw 12-bit code, (channel=0–3, code=0–4095) → void
                                                                    // clamps to [0, 4095]; writes channel C only
    dac.setAll([0.1d, 0.2d, 0.3d, 0.4d] as double[])               // update all four channels simultaneously, (fractions[4]) → void
                                                                    // single 8-byte Fast Write transaction; EEPROM unaffected
    dac.setVoltageEeprom(0, 0.5d, 0, 1)                            // set channel A and persist to EEPROM, (channel, fraction, vref=0/1, gain=1/2) → void
                                                                    // Single Write updates DAC register and nonvolatile EEPROM
    dac.setRawEeprom(1, 2048, 0, 1)                                // set channel B raw code and persist, (channel, code, vref=0/1, gain=1/2) → void
                                                                    // Single Write; takes up to 50 ms for EEPROM write
    dac.setAllEeprom([0.0d, 0.25d, 0.5d, 0.75d] as double[],       // update all four channels + EEPROM, (fractions[4], vrefs[4], gains[4]) → void
                     [0, 0, 0, 0] as int[],
                     [1, 1, 1, 1] as int[])
                                                                    // Sequential Write from A to D; persists all four at the end
    dac.setVref(0, 0, 0, 0)                                        // set V_REF for all four channels, (vref_a, vref_b, vref_c, vref_d) → void
                                                                    // 0 = external V_DD; volatile register only
    dac.setGain(1, 1, 1, 1)                                        // set gain for all four channels, (gain_a, gain_b, gain_c, gain_d) → void
                                                                    // 1 = ×1, 2 = ×2; volatile register only
    dac.setPowerDown(0, 0, 0, 0)                                   // set power-down for all four channels, (pd_a, pd_b, pd_c, pd_d) → void
                                                                    // 0 = normal, 1 = 1 kΩ, 2 = 100 kΩ, 3 = 500 kΩ to GND
    Thread.sleep(50)                                               // wait for EEPROM write to complete, (ms) → void

    def state = dac.read()                                         // read all four channels' DAC and EEPROM state, () → ChannelState[4]
                                                                    // 4 ChannelState entries with code, vref, gain, power_down, eeprom_*
    printf("ch0 code=%d eeprom_ready=true%n", state[0].code)

    dac.softwareUpdate()                                           // latch all V_OUT simultaneously, () → void
                                                                    // General Call 0x00, 0x08; equivalent to LDAC pin pulse
    dac.wakeUp()                                                   // clear all PD bits via General Call Wake-Up, () → void
                                                                    // sends 0x00, 0x09; clears power-down on all four channels
    dac.reset()                                                    // reload EEPROM into all DAC registers, () → void
                                                                    // General Call 0x00, 0x06; triggers internal POR

    def ready = dac.isEepromReady()                                // check if EEPROM write is complete, () → boolean
                                                                    // true when no EEPROM write is in progress
    println("EEPROM ready: $ready")
} finally {
    transport.close()
    gcTransport.close()
}
