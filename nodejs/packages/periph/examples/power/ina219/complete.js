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

ina.configure(INA219Full.BRNG_32V, INA219Full.PGA_8, INA219Full.ADC_12BIT, INA219Full.ADC_12BIT, INA219Full.MODE_SHUNT_BUS_CONT);

console.log(ina.conversionReady());
console.log(ina.overflow());

ina.shutdown();
ina.wake();

ina.trigger();

ina.reset();

transport.close();