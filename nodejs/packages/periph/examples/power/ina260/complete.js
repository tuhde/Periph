'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA260Full } = require('../../../src/chips/power/ina260');

const connection = new I2CConnection(1, 0x40);
const ina = new INA260Full(connection);

(async () => {
    console.log('0x' + (await ina.manufacturerId()).toString(16));
    console.log('0x' + (await ina.dieId()).toString(16));

    console.log(await ina.voltage());
    console.log(await ina.current());
    console.log(await ina.power());
    console.log(await ina.conversionReady());
    console.log(await ina.overflow());

    await ina.configure(INA260Full.AVG_64, INA260Full.CT_1100US, INA260Full.CT_1100US, INA260Full.MODE_SHUNT_BUS_CONT);

    await ina.setAlert(INA260Full.POL, 1.0, false, true);
    console.log('0x' + (await ina.alertFlags()).toString(16));

    await ina.setAlert(INA260Full.BOL, 5.5);
    await ina.setAlert(INA260Full.BUL, 4.5);
    await ina.setAlert(INA260Full.OCL, 5.0);
    await ina.setAlert(INA260Full.CNVR);
    await ina.clearAlert();

    await ina.trigger(INA260Full.MODE_SHUNT_BUS_TRIGGERED);
    while (!(await ina.conversionReady())) {}

    await ina.shutdown();
    await ina.wake();
    await ina.reset();

    await connection.close();
})();
