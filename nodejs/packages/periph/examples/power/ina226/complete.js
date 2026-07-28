'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA226Full } = require('../../../src/chips/power/ina226');

const connection = new I2CConnection(1, 0x40);
const ina = new INA226Full(connection);

(async () => {
    console.log('0x' + (await ina.manufacturerId()).toString(16));
    console.log('0x' + (await ina.dieId()).toString(16));

    console.log(await ina.voltage());
    console.log(await ina.shuntVoltage());
    console.log(await ina.current());
    console.log(await ina.power());
    console.log(await ina.conversionReady());
    console.log(await ina.overflow());

    await ina.configure(3, 4, 4, 7);

    await ina.setAlert(INA226Full.POL, 1.0, false, true);
    console.log('0x' + (await ina.alertFlags()).toString(16));

    await ina.setAlert(INA226Full.BOL, 5.5);
    await ina.setAlert(INA226Full.BUL, 4.5);
    await ina.setAlert(INA226Full.SOL, 0.05);
    await ina.setAlert(INA226Full.CNVR);

    await ina.shutdown();
    await ina.wake();
    await ina.reset();

    await connection.close();
})();
