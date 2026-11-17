'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA260Full } = require('../../../src/chips/power/ina260');

const connection = new I2CConnection(1, 0x40);
const ina = new INA260Full(connection);

(async () => {
    // 64-sample averaging smooths out switching noise from DC/DC converters
    await ina.configure(INA260Full.AVG_64, INA260Full.CT_1100US, INA260Full.CT_1100US, INA260Full.MODE_SHUNT_BUS_CONT);

    // latch the alert so a brief spike is not missed between loop iterations
    await ina.setAlert(INA260Full.POL, 1.0, false, true);

    console.log('V          A          W');

    setInterval(async () => {
        // wait for a fresh conversion to avoid reading stale register values
        while (!(await ina.conversionReady())) {}

        const v = await ina.voltage();
        const i = await ina.current();
        const p = await ina.power();
        console.log(v.toFixed(3) + 'V   ' + i.toFixed(4) + 'A   ' + p.toFixed(4) + 'W');

        // reading alertFlags clears the latch — do this after printing measurements
        if ((await ina.alertFlags()) & INA260Full.AFF) {
            console.log('ALERT: power limit exceeded');
        }
    }, 1000);
})();
