'use strict';

// Complete example: exercise every method in the public API.
// Sets the wiper by code, voltage and ratio, reads it back, steps with
// increment, inspects the MODE bit, stores the power-up position in the
// EEPROM and changes the endpoint voltages.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS3502Full } = require('periph/src/chips/adc_dac/ds3502');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DS3502Full.I2C_ADDRESS);
    const pot = new DS3502Full(connection, 15.0, 0.0);                      // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    await pot.init();                                                       // Run initialization sequence, () → void (CR = 0x80, caches WR)

    await pot.setWiper(100);                                                // Set wiper position, (code 0–127) → void
    // writes WR only; the EEPROM is not touched
    console.log(await pot.getWiper());                                      // Read wiper position, () → int 0–127
    // reads WR; always the live wiper, never the IVR
    await pot.setVoltage(6.0);                                              // Set RW output voltage, (volts V) → void
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    console.log(await pot.getVoltage());                                    // Read unloaded RW voltage, () → float V
    await pot.setRatio(0.25);                                               // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 127)
    console.log(await pot.getRatio());                                      // Read divider ratio, () → float
    console.log(await pot.getResistance('L'));                              // Read wiper-to-RL resistance, (terminal='L') → float Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    console.log(await pot.getResistance('H'));                              // Read wiper-to-RH resistance, (terminal='L') → float Ω
    console.log(await pot.increment(5));                                    // Step the wiper, (steps=1) → int 0–127
    // read, add, clamp, write; returns the new code
    console.log(await pot.getMode());                                       // Read MODE, () → bool
    // true if writes to 0x00 also program the EEPROM
    await pot.setMode(false);                                               // Select SRAM-only writes, (eeprom bool) → void
    // CR = 0x80; true would write CR = 0x00
    await pot.storeWiper(64);                                               // Store power-up position, (code) → void
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    await pot.waitReady();                                                  // Acknowledge polling, (timeoutMs=30) → void
    // throws if the chip keeps NACKing
    pot.setEndpoints(12.0, 2.0);                                            // Change RH / RL voltages, (vHigh V, vLow=0.0 V) → void
    // affects the conversions only; no I²C traffic

    await connection.close();
})();
