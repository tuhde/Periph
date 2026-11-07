'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Mcp4xxxFull } = require('../../../src/chips/adc_dac/mcp4xxx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x2C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
// MCP4651: dual 8-bit potentiometer (volatile); the non-volatile calls at the end need e.g. an MCP4661.
const pot = new Mcp4xxxFull(connection, 'MCP4651', 10000.0, 75.0);  // Create MCP4XXX driver, (connection, part='MCP4532', rAb=10000 Ω, rWiper=75 Ω, hvc=null, hvcSettleMs=1) → Mcp4xxxFull

(async () => {
    await pot.init();                                       // Bus check, () → void
                                                            // reads wipers and TCON, no writes

    // --- Part properties ---
    console.log(pot.channels());                            // Number of networks, () → int 1-2
                                                            // property of the selected part
    console.log(pot.steps());                               // Number of wiper positions, () → int 129 or 257
                                                            // max code + 1
    console.log(pot.isPotentiometer());                     // Terminal A on a pin, () → bool
                                                            // true for the X1 parts
    console.log(pot.isNonvolatile());                       // Has EEPROM, () → bool
                                                            // true for 454X / 456X / 464X / 466X

    // --- Wiper position and resistance ---
    await pot.setWiper(100, 0);                             // Set wiper, (code 0-256, channel=0) → void
                                                            // writes [(0 << 4) | D8, code & 0xFF]; code clamped
    console.log(await pot.getWiper(0));                     // Read wiper, (channel=0) → int 0-256
                                                            // two-byte read of the wiper register
    await pot.setBoth(10, 20);                              // Set both wipers, (code0, code1) → void
                                                            // dual parts only; two transactions
    await pot.setResistance(2200.0, 1);                     // Set W-B resistance, (ohms, channel=0) → void
                                                            // nearest code of R = N/max*RAB + RW, clamped
    console.log(await pot.getResistance(1));                // Read W-B resistance, (channel=0) → float Ω
                                                            // N/max*RAB + RW
    console.log(pot.getResistanceStep());                   // Resistance of one step, () → float Ω
                                                            // RAB / max code

    // --- Ratio and W-A resistance ---
    await pot.setRatio(0.5, 0);                             // Set wiper ratio, (ratio 0.0-1.0, channel=0) → void
                                                            // code = round(ratio * max code)
    console.log(await pot.getRatio(0));                     // Read wiper ratio, (channel=0) → float 0.0-1.0
                                                            // code / max code
    await pot.setResistanceWa(3300.0, 0);                   // Set W-A resistance, (ohms, channel=0) → void
                                                            // potentiometer parts only
    console.log(await pot.getResistanceWa(0));              // Read W-A resistance, (channel=0) → float Ω
                                                            // (max code - N)/max*RAB + RW

    // --- Increment / decrement ---
    console.log(await pot.increment(8, 0));                 // Step wiper up, (steps=1, channel=0) → int
                                                            // one continuous transaction of increment commands, saturating
    console.log(await pot.decrement(4, 0));                 // Step wiper down, (steps=1, channel=0) → int
                                                            // same with decrement commands, saturating at 0

    // --- Shutdown and terminals ---
    await pot.shutdown(1);                                  // Shut network down, (channel=0) → void
                                                            // TCON.RxHW = 0: A open, W tied to B; wiper kept
    console.log(await pot.isShutdown(1));                   // Read shutdown state, (channel=0) → bool
                                                            // from TCON
    await pot.resume(1);                                    // Leave shutdown, (channel=0) → void
                                                            // TCON.RxHW = 1
    await pot.setTerminals(true, false, true, 0);           // Connect terminals, (a=true, w=true, b=true, channel=0) → void
                                                            // TCON.RxA / RxW / RxB, other bits preserved
    console.log(await pot.getTerminals(0));                 // Read terminals, (channel=0) → {a, w, b}
                                                            // from TCON
    await pot.setTerminals(true, true, true, 0);            // Reconnect all terminals, (a=true, w=true, b=true, channel=0) → void
                                                            // restores the wiper terminal
    console.log(await pot.getTcon());                       // Read TCON, () → int 0-0x1FF
                                                            // raw terminal control register
    await pot.setGeneralCall(true);                         // Set General Call, (enabled) → void
                                                            // TCON.GCEN; chip answers address 0x00 commands

    // --- Raw access, model, reset ---
    console.log(await pot.readRegister(0x04));              // Raw read, (address 0-15) → int 0-0x1FF
                                                            // nine-bit value of any address the part has
    await pot.writeRegister(0x04, 0x1FF);                   // Raw write, (address, value) → void
                                                            // volatile wipers, TCON (and EEPROM addresses on NV parts)
    pot.setRab(10000.0, 75.0);                              // Set resistance model, (rAb Ω, rWiper Ω) → void
                                                            // no I²C traffic; affects conversions only
    await pot.reset();                                      // Emulate power-on reset, () → void
                                                            // mid-scale (or EEPROM wiper) in every wiper, TCON 0x1FF

    // --- Non-volatile parts only (MCP454X / 456X / 464X / 466X) ---
    // console.log(await pot.readStatus());   // Read STATUS, () → {eepromBusy, wiperLock, writeProtect}
    // await pot.waitReady(20);               // Wait for the EEPROM write cycle, (timeoutMs=20 ms) → void
    // console.log(await pot.getNvWiper(0));  // Read EEPROM wiper, (channel=0) → int
    // await pot.setNvWiper(64, 0);           // Write EEPROM wiper, (code, channel=0) → void
    // console.log(await pot.storeWiper(0));  // Store current wiper, (channel=0) → int
    // console.log(await pot.readData(0));    // Read EEPROM register, (index 0-9) → int 0-0x1FF
    // await pot.writeData(0, 0x155);         // Write EEPROM register, (index 0-9, value 0-0x1FF) → void
    // console.log(await pot.isWiperLocked(0));    // Read WiperLock, (channel=0) → bool
    // console.log(await pot.isWriteProtected());  // Read write protect, () → bool
    // await pot.setWiperLock(true, 0);       // High-voltage command, (locked, channel=0) → void (needs hvc pin)
    // await pot.setWriteProtect(true);       // High-voltage command, (enabled) → void (needs hvc pin)
})();
