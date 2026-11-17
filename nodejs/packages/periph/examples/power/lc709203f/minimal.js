'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LC709203FMinimal } = require('../../../src/chips/power/lc709203f');

const connection = new I2CConnection(1, 0x0B);              // Create I²C connection, (bus, addr=0x0B) → Connection
const gauge = new LC709203FMinimal(connection, 1000, 1);    // Create LC709203F driver, (connection, capacityMah, profile)

setInterval(async () => {
    console.log(await gauge.voltage(), await gauge.percent(), await gauge.temperature());   // Read V / % / °C, () → Promise<number> each
}, 5000);
