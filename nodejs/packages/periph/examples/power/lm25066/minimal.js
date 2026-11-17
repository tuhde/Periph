'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LM25066Minimal } = require('../../../src/chips/power/lm25066');

const connection = new I2CConnection(1, 0x40);              // Create I²C connection, (bus, addr=0x40) → Connection
const hotswap = new LM25066Minimal(connection, 1.0, false); // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

setInterval(async () => {
    console.log(await hotswap.voltageIn(), await hotswap.voltageOut());                              // Read VIN / VOUT, () → Promise<number> V each
    console.log(await hotswap.current(), await hotswap.power(), await hotswap.temperature());        // Read A / W / MOSFET °C, () → Promise<number> each
}, 1000);
