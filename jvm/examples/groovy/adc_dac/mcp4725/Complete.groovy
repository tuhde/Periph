///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4725Full

def transport   = new I2CTransport(1, 0x60)              // open device transport, (bus, address) → I2CTransport
def gcTransport = new I2CTransport(1, 0x00)              // open general call transport, (bus, address=0x00) → I2CTransport
try {
    def dac = new Mcp4725Full(transport, gcTransport)           // construct driver, (transport, generalCall) → Mcp4725Full

    dac.setVoltage(0.5)                                         // set output to 50% of VDD, (fraction=0.0–1.0) → void
                                                                // converts fraction to 12-bit code and issues a Fast Write
    dac.setRaw(2048)                                            // set output to raw code, (code=0–4095) → void
                                                                // maps directly to DAC register; 2048 ≈ 50% of VDD
    dac.setVoltageEeprom(0.75)                                  // set output and persist to EEPROM, (fraction=0.0–1.0) → void
                                                                // DAC register and EEPROM are both written; output survives power cycle
    dac.setRawEeprom(3072)                                      // set raw code and persist to EEPROM, (code=0–4095) → void
                                                                // issues a Write DAC + EEPROM command; takes up to 50 ms

    Thread.sleep(50)                                            // wait for EEPROM write to complete, (ms) → void

    def state = dac.read()                                      // read device state, () → ReadResult
                                                                // returns DAC register, EEPROM contents, power-down mode, and RDY/BSY flag
    printf("code=%d fraction=%.4f pd=%d eepromCode=%d eepromPd=%d ready=%b%n",
           state.code, state.voltageFraction, state.powerDown,
           state.eepromCode, state.eepromPowerDown, state.eepromReady)

    dac.setPowerDown(1)                                         // enter power-down mode, (mode=0–3) → void
                                                                // 1 = 1 kΩ pull-down to GND; output pin goes high-Z
    Thread.sleep(100)

    dac.wakeUp()                                                // send General Call Wake-Up to all MCP47xx on bus, () → void
                                                                // clears power-down bits in the DAC register; output resumes
    dac.reset()                                                 // send General Call Reset to all MCP47xx on bus, () → void
                                                                // triggers internal POR; reloads EEPROM into DAC register

    def ready = dac.isEepromReady()                            // read RDY/BSY bit, () → boolean
                                                                // true when no EEPROM write is in progress
    println("EEPROM ready: $ready")

} finally {
    transport.close()
    gcTransport.close()
}
