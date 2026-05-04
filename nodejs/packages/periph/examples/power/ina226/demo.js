'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA226Full } = require('../../../src/chips/power/ina226');

const transport = new I2CTransport(1, 0x40);
const ina = new INA226Full(transport);

// 64-sample averaging smooths out switching noise from DC/DC converters
ina.configure(3, 4, 4, 7);

// latch the alert so a brief spike is not missed between loop iterations
ina.setAlert(INA226Full.POL, 1.0, false, true);

console.log('V          A          W');

setInterval(() => {
    // wait for a fresh conversion to avoid reading stale register values
    while (!ina.conversionReady()) {}

    const v = ina.voltage();
    const i = ina.current();
    const p = ina.power();
    console.log(v.toFixed(3) + 'V   ' + i.toFixed(4) + 'A   ' + p.toFixed(4) + 'W');

    // reading alertFlags clears the latch — do this after printing measurements
    if (ina.alertFlags() & INA226Full.POL) {
        console.log('ALERT: power limit exceeded');
    }
}, 1000);
