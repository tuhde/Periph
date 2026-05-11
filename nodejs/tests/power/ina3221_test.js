'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { INA3221Full }  = require('../../packages/periph/src/chips/power/ina3221');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log(`FAIL ${label}: got 0x${got.toString(16).toUpperCase().padStart(4,'0')}, expected 0x${expected.toString(16).toUpperCase().padStart(4,'0')}`);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const ina = new INA3221Full(transport);

checkEq('manufacturer_id', ina.manufacturerId(), 0x5449);
checkEq('die_id',          ina.dieId(),          0x3220);

for (const ch of [1, 2, 3]) {
    checkTrue(`ch${ch} voltage non-negative`, ina.voltage(ch) >= 0.0);
    checkTrue(`ch${ch} shunt_voltage finite`,  Math.abs(ina.shuntVoltage(ch)) < 1.0);
    checkTrue(`ch${ch} current finite`,         Math.abs(ina.current(ch)) < 100.0);
    checkTrue(`ch${ch} power non-negative`,    ina.power(ch) >= 0.0);
}

checkTrue('conversion_ready', ina.conversionReady());

ina.configure(3, 4, 4, 7);
checkEq('configure: mfr_id still valid', ina.manufacturerId(), 0x5449);

ina.setCriticalAlert(1, 0.1);
ina.setWarningAlert(2, 0.05);
const flags = ina.alertFlags();
checkTrue('alert_flags readable', flags >= 0);

ina.enableChannel(1, false);
checkTrue('channel 1 disabled', !ina.channelEnabled(1));
ina.enableChannel(1, true);
checkTrue('channel 1 re-enabled', ina.channelEnabled(1));

ina.setSummationChannels([1, 2], 0.2);
const svSum = ina.summationValue();
checkTrue('summation_value finite', Math.abs(svSum) < 10.0);

ina.setPowerValidLimits(5.5, 4.5);
checkTrue('power_valid readable', typeof ina.powerValid() === 'boolean');

ina.shutdown();
const end = Date.now() + 1;
while (Date.now() < end) {}
ina.wake();
checkTrue('wake: voltage non-negative', ina.voltage(1) >= 0.0);

ina.reset();
checkEq('reset: mfr_id still valid', ina.manufacturerId(), 0x5449);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);