'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA226Full } = require('../../../src/chips/power/ina226');

const connection = new I2CConnection(1, 0x40);
const ina = new INA226Full(connection);

(async () => {
    // 64-sample averaging smooths out switching noise from DC/DC converters
    await ina.configure(3, 4, 4, 7);

    // latch the alert so a brief spike is not missed between loop iterations
    await ina.setAlert(INA226Full.POL, 1.0, false, true);

    console.log('V          A          W');

    setInterval(async () => {
        // wait for a fresh conversion to avoid reading stale register values
        while (!(await ina.conversionReady())) {}

        const v = await ina.voltage();
        const i = await ina.current();
        const p = await ina.power();
        console.log(v.toFixed(3) + 'V   ' + i.toFixed(4) + 'A   ' + p.toFixed(4) + 'W');

        // reading alertFlags clears the latch — do this after printing measurements
        if ((await ina.alertFlags()) & INA226Full.AFF) {
            console.log('ALERT: power limit exceeded');
        }
    }, 1000);
})();
