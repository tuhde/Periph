'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Full } = require('../../../src/chips/power/ina219');

const transport = new I2CTransport(1, 0x40);
const ina = new INA219Full(transport);

// poll once per second for 10 seconds to characterize a power rail
console.log('V          A          W');
let vMin, vMax, vSum = 0;
let iMin, iMax, iSum = 0;
let pMin, pMax, pSum = 0;

for (let i = 0; i < 10; i++) {
    // switch on the load at sample 5 to see the step in current and power

    while (!ina.conversionReady()) {}

    const v = ina.voltage();
    const c = ina.current();
    const p = ina.power();
    console.log(v.toFixed(3) + 'V   ' + c.toFixed(4) + 'A   ' + p.toFixed(4) + 'W');

    if (i === 0) {
        vMin = vMax = v;
        iMin = iMax = c;
        pMin = pMax = p;
    } else {
        vMin = Math.min(vMin, v);
        vMax = Math.max(vMax, v);
        iMin = Math.min(iMin, c);
        iMax = Math.max(iMax, c);
        pMin = Math.min(pMin, p);
        pMax = Math.max(pMax, p);
    }
    vSum += v;
    iSum += c;
    pSum += p;
}

console.log('V: min=' + vMin.toFixed(3) + ' max=' + vMax.toFixed(3) + ' mean=' + (vSum / 10).toFixed(3));
console.log('I: min=' + iMin.toFixed(4) + ' max=' + iMax.toFixed(4) + ' mean=' + (iSum / 10).toFixed(4));
console.log('P: min=' + pMin.toFixed(4) + ' max=' + pMax.toFixed(4) + ' mean=' + (pSum / 10).toFixed(4));

transport.close();
