'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Minimal } = require('../../../src/chips/power/ina219');

const transport = new I2CTransport(1, 0x40);
const ina = new INA219Minimal(transport);

setInterval(() => {
    console.log(ina.voltage(), ina.shuntVoltage(), ina.current(), ina.power());
}, 1000);