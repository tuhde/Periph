'use strict';

// Minimal example for the AXP2101 — battery and power-source monitoring.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AXP2101Minimal } = require('periph/src/chips/power/axp2101');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x34', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const chip = new AXP2101Minimal(connection);               // Create AXP2101 driver, (connection)

(async () => {
    for (let i = 0; i < 10; i++) {
        const v = await chip.batteryVoltage();                // Read battery voltage, () → number V
        const pct = await chip.batteryPercent();              // Read state of charge, () → number %
        const state = await chip.chargeState();               // Read charge state, () → int (0 trickle … 5 none)
        const vbus = await chip.vbusPresent();                // Check input source, () → boolean
        console.log(`${v.toFixed(3)} V  ${pct} %  state=${state}  vbus=${vbus}`);
        await new Promise(r => setTimeout(r, 1000));
    }
    await connection.close();
})();
