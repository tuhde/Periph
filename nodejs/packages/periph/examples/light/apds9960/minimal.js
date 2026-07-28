'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { APDS9960Minimal } = require('../../../src/chips/light/apds9960');

const connection = new I2CConnection(1, 0x39);
const apds = new APDS9960Minimal(connection);               // Create APDS9960 driver, (connection) → APDS9960Minimal

setInterval(async () => {
    const { clear, red, green, blue } = await apds.color(); // Read all RGBC channels, () → { clear, red, green, blue }
    console.log(`C=${clear} R=${red} G=${green} B=${blue}`);
}, 1000);
