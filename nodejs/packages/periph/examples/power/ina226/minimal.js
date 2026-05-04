'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA226Minimal } = require('../../../src/chips/power/ina226');

const transport = new I2CTransport(1, 0x40);
const ina = new INA226Minimal(transport);

setInterval(() => {
    console.log(ina.voltage(), ina.current(), ina.power());
}, 1000);
