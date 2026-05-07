'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Full } = require('../../../src/chips/power/ina219');

const transport = new I2CTransport(1, 0x40);
const ina = new INA219Full(transport);

console.log(ina.voltage());
console.log(ina.shuntVoltage());
console.log(ina.current());
console.log(ina.power());
console.log(ina.conversionReady());
console.log(ina.overflow());

ina.configure(1, 3, 3, 3, 7);

ina.shutdown();
ina.wake();

ina.reset();

transport.close();
