'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Full } = require('../../../src/chips/power/ina219');

const transport = new I2CTransport(1, 0x40);
const ina = new INA219Full(transport);

const readings = [];
for (let n = 0; n < 10; n++) {
    const v = ina.voltage();
    const i = ina.current();
    const p = ina.power();
    readings.push({ v, i, p });
    console.log(v.toFixed(3) + 'V   ' + i.toFixed(4) + 'A   ' + p.toFixed(4) + 'W');
    if (n === 3) {
        console.log('--- switch on load now ---');
    }
    // 1 s synchronous delay
    const end = Date.now() + 1000;
    while (Date.now() < end) {}
}

for (const key of ['v', 'i', 'p']) {
    const vals = readings.map(r => r[key]);
    const min = Math.min(...vals);
    const max = Math.max(...vals);
    const mean = vals.reduce((a, b) => a + b, 0) / vals.length;
    console.log(key.toUpperCase() + ' min=' + min.toFixed(4) + ' max=' + max.toFixed(4) + ' mean=' + mean.toFixed(4));
}

transport.close();
