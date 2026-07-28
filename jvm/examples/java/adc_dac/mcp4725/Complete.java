///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.Mcp4725Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x60);         // open device connection, (bus, address) → I2CConnection
             var gcConnection = new I2CConnection(1, 0x00)) {     // open general call connection, (bus, address=0x00) → I2CConnection

            var dac = new Mcp4725Full(connection, gcConnection);         // construct driver, (connection, generalCall) → Mcp4725Full

            dac.setVoltage(0.5);                                        // set output to 50% of VDD, (fraction=0.0–1.0) → void
                                                                        // converts fraction to 12-bit code and issues a Fast Write
            dac.setRaw(2048);                                           // set output to raw code, (code=0–4095) → void
                                                                        // maps directly to DAC register; 2048 ≈ 50% of VDD
            dac.setVoltageEeprom(0.75);                                 // set output and persist to EEPROM, (fraction=0.0–1.0) → void
                                                                        // DAC register and EEPROM are both written; output survives power cycle
            dac.setRawEeprom(3072);                                     // set raw code and persist to EEPROM, (code=0–4095) → void
                                                                        // issues a Write DAC + EEPROM command; takes up to 50 ms

            Thread.sleep(50);                                           // wait for EEPROM write to complete, (ms) → void

            var state = dac.read();                                     // read device state, () → ReadResult
                                                                        // returns DAC register, EEPROM contents, power-down mode, and RDY/BSY flag
            System.out.printf("code=%d fraction=%.4f pd=%d eepromCode=%d eepromPd=%d ready=%b%n",
                    state.code(), state.voltageFraction(), state.powerDown(),
                    state.eepromCode(), state.eepromPowerDown(), state.eepromReady());

            dac.setPowerDown(1);                                        // enter power-down mode, (mode=0–3) → void
                                                                        // 1 = 1 kΩ pull-down to GND; output pin goes high-Z
            Thread.sleep(100);

            dac.wakeUp();                                               // send General Call Wake-Up to all MCP47xx on bus, () → void
                                                                        // clears power-down bits in the DAC register; output resumes
            dac.reset();                                                // send General Call Reset to all MCP47xx on bus, () → void
                                                                        // triggers internal POR; reloads EEPROM into DAC register

            boolean ready = dac.isEepromReady();                       // read RDY/BSY bit, () → boolean
                                                                        // true when no EEPROM write is in progress
            System.out.println("EEPROM ready: " + ready);

        }
    }
}
