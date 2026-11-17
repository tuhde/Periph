'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA260Minimal } = require('../../../src/chips/power/ina260');

const connection = new I2CConnection(1, 0x40);
const ina = new INA260Minimal(connection);

setInterval(async () => {
    console.log(await ina.voltage(), await ina.current(), await ina.power());
}, 1000);
