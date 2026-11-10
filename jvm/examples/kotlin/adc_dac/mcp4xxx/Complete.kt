///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.MCP4XXXFull

fun main() {
    I2CConnection(1, 0x28).use { connection ->                             // open I²C bus 1, device 0x28, (bus, address) → I2CConnection
        // MCP4661: dual 8-bit potentiometer with EEPROM (the non-volatile calls need such a part).
        val pot = MCP4XXXFull(connection, "MCP4661", 10000.0, 75.0)         // construct driver, (connection, part, rAb=10000 Ω, rWiper=75 Ω, hvc=null, hvcSettleMs=1) → MCP4XXXFull
        pot.init()                                                          // bus check, () → Unit
        
        // --- Part properties ---
        println(pot.channels())                                       // number of networks, () → int 1-2
                                                                      // property of the selected part
        println(pot.steps())                                          // number of wiper positions, () → int 129 or 257
                                                                      // max code + 1
        println(pot.isPotentiometer())                                // terminal A on a pin, () → boolean
                                                                      // true for the X1 parts
        println(pot.isNonvolatile())                                  // has EEPROM, () → boolean
                                                                      // true for 454X / 456X / 464X / 466X
        
        // --- Wiper position and resistance ---
        pot.setWiper(100, 0)                                          // set wiper, (code 0-256, channel=0) → void
                                                                      // writes [(0 << 4) | D8, code & 0xFF]; code clamped
        println(pot.getWiper(0))                                      // read wiper, (channel=0) → int 0-256
                                                                      // two-byte read of the wiper register
        pot.setBoth(10, 20)                                           // set both wipers, (code0, code1) → void
                                                                      // dual parts only; two transactions
        pot.setResistance(2200.0, 1)                                  // set W-B resistance, (ohms, channel=0) → void
                                                                      // nearest code of R = N/max*RAB + RW, clamped
        println(pot.getResistance(1))                                 // read W-B resistance, (channel=0) → double Ω
                                                                      // N/max*RAB + RW
        println(pot.getResistanceStep())                              // resistance of one step, () → double Ω
                                                                      // RAB / max code
        
        // --- Ratio and W-A resistance ---
        pot.setRatio(0.5, 0)                                          // set wiper ratio, (ratio 0.0-1.0, channel=0) → void
                                                                      // code = round(ratio * max code)
        println(pot.getRatio(0))                                      // read wiper ratio, (channel=0) → double 0.0-1.0
                                                                      // code / max code
        pot.setResistanceWa(3300.0, 0)                                // set W-A resistance, (ohms, channel=0) → void
                                                                      // potentiometer parts only
        println(pot.getResistanceWa(0))                               // read W-A resistance, (channel=0) → double Ω
                                                                      // (max code - N)/max*RAB + RW
        
        // --- Increment / decrement ---
        println(pot.increment(8, 0))                                  // step wiper up, (steps=1, channel=0) → int
                                                                      // one continuous transaction of increment commands, saturating
        println(pot.decrement(4, 0))                                  // step wiper down, (steps=1, channel=0) → int
                                                                      // same with decrement commands, saturating at 0
        
        // --- Shutdown and terminals ---
        pot.shutdown(1)                                               // shut network down, (channel=0) → void
                                                                      // TCON.RxHW = 0: A open, W tied to B; wiper kept
        println(pot.isShutdown(1))                                    // read shutdown state, (channel=0) → boolean
                                                                      // from TCON
        pot.resume(1)                                                 // leave shutdown, (channel=0) → void
                                                                      // TCON.RxHW = 1
        pot.setTerminals(true, false, true, 0)                        // connect terminals, (a, w, b, channel=0) → void
                                                                      // TCON.RxA / RxW / RxB, other bits preserved
        println(pot.getTerminals(0))                                  // read terminals, (channel=0) → (a, w, b)
                                                                      // from TCON
        pot.setTerminals(true, true, true, 0)                         // reconnect all terminals, (a, w, b, channel=0) → void
                                                                      // restores the wiper terminal
        println(pot.getTcon())                                        // read TCON, () → int 0-0x1FF
                                                                      // raw terminal control register
        pot.setGeneralCall(true)                                      // set General Call, (enabled) → void
                                                                      // TCON.GCEN; chip answers address 0x00 commands
        
        // --- Raw access, model, reset ---
        println(pot.readRegister(0x04))                               // raw read, (address 0-15) → int 0-0x1FF
                                                                      // nine-bit value of any address the part has
        pot.writeRegister(0x04, 0x1FF)                                // raw write, (address, value) → void
                                                                      // volatile wipers, TCON, EEPROM addresses on NV parts
        pot.setRab(10000.0, 75.0)                                     // set resistance model, (rAb Ω, rWiper Ω; negative keeps RW) → void
                                                                      // no I²C traffic; affects conversions only
        
        // --- Non-volatile parts (MCP454X / 456X / 464X / 466X) ---
        println(pot.readStatus().eepromBusy)                          // read STATUS, () → Status
                                                                      // EEPROM busy flag; Status also has wiperLock and writeProtect
        pot.waitReady(20)                                             // wait for EEPROM write cycle, (timeoutMs=20 ms) → void
                                                                      // polls STATUS.EEWA
        println(pot.getNvWiper(0))                                    // read EEPROM wiper, (channel=0) → int
                                                                      // power-up value
        pot.setNvWiper(64, 0)                                         // write EEPROM wiper, (code, channel=0) → void
                                                                      // volatile wiper unchanged; waits for the write cycle
        println(pot.storeWiper(0))                                    // store current wiper, (channel=0) → int
                                                                      // reads the volatile wiper, writes it to EEPROM
        println(pot.readData(0))                                      // read EEPROM register, (index 0-9) → int 0-0x1FF
                                                                      // general-purpose 9-bit register
        pot.writeData(0, 0x155)                                       // write EEPROM register, (index 0-9, value 0-0x1FF) → void
                                                                      // uses one of 1 000 000 write cycles
        println(pot.isWiperLocked(0))                                 // read WiperLock, (channel=0) → boolean
                                                                      // STATUS.WLx
        println(pot.isWriteProtected())                               // read write protect, () → boolean
                                                                      // STATUS.WP
        pot.reset()                                                   // emulate power-on reset, () → void
                                                                      // EEPROM wiper (or mid-scale) in every wiper, TCON 0x1FF
    }
}
