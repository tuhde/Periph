'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA226Full } = require('../../../src/chips/power/ina226');

const transport = new I2CTransport(1, 0x40);
const ina = new INA226Full(transport);

console.log('0x' + ina.manufacturerId().toString(16));
console.log('0x' + ina.dieId().toString(16));

console.log(ina.voltage());
console.log(ina.shuntVoltage());
console.log(ina.current());
console.log(ina.power());
console.log(ina.conversionReady());
console.log(ina.overflow());

ina.configure(3, 4, 4, 7);

ina.setAlert(INA226Full.POL, 1.0, false, true);
console.log('0x' + ina.alertFlags().toString(16));

ina.setAlert(INA226Full.BOL, 5.5);
ina.setAlert(INA226Full.BUL, 4.5);
ina.setAlert(INA226Full.SOL, 0.05);
ina.setAlert(INA226Full.CNVR);

ina.shutdown();
ina.wake();
ina.reset();

transport.close();
