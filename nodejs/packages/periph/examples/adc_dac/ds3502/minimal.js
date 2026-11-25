'use strict';

// Minimal example: set and read back a high-voltage digital potentiometer.
// Constructs the driver with RH = 15 V, RL = 0 V and steps the wiper through
// a few positions, printing the unloaded output voltage.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS3502Minimal } = require('periph/src/chips/adc_dac/ds3502');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DS3502Minimal.I2C_ADDRESS);
    const pot = new DS3502Minimal(connection, 15.0, 0.0);                   // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    await pot.init();                                                       // Run initialization sequence, () → void (CR = 0x80, caches WR)

    for (const code of [0, 32, 64, 96, 127]) {
        await pot.setWiper(code);                                           // Set wiper position, (code 0–127) → void
        console.log(code, await pot.getWiper());                            // Read wiper position, () → int 0–127
        console.log(await pot.getVoltage());                                // Read unloaded RW voltage, () → float V
        await sleep(500);
    }

    await pot.setVoltage(6.0);                                              // Set RW output voltage, (volts V) → void

    await connection.close();
})();
