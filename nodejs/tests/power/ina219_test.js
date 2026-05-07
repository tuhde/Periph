'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { INA219Full }   = require('../../packages/periph/src/chips/power/ina219');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const ina = new INA219Full(transport);

checkTrue('voltage non-negative', ina.voltage()      >= 0.0);
checkTrue('shunt_voltage finite', ina.shuntVoltage() > -1.0);
checkTrue('current finite',       ina.current()      > -10.0);
checkTrue('power non-negative',   ina.power()        >= 0.0);

checkTrue('conversion_ready', ina.conversionReady());
checkTrue('no overflow',      !ina.overflow());

ina.configure(1, 3, 3, 3, 7);
checkTrue('voltage after configure', ina.voltage() >= 0.0);

ina.shutdown();
const end = Date.now() + 1;
while (Date.now() < end) {}
ina.wake();
checkTrue('wake: voltage non-negative', ina.voltage() >= 0.0);

ina.reset();
checkTrue('reset: voltage non-negative', ina.voltage() >= 0.0);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
