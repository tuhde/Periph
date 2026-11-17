'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LTC4151Minimal } = require('../../../src/chips/power/ltc4151');

const connection = new I2CConnection(1, 0x6A);                            // Create I²C connection, (bus=1, addr=0x6A) → Connection
const ltc = new LTC4151Minimal(connection, 0.02);                         // Create LTC4151 driver, (connection, rSense=0.02 Ω) → void

setInterval(async () => {
    console.log(await ltc.voltage(), await ltc.current(), await ltc.power());   // Read V / A / W, () → number each
}, 1000);
