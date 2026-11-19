'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { MPL115A2Minimal } = require('../../../src/chips/pressure/mpl115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const baro = new MPL115A2Minimal(connection);           // Create MPL115A2 driver, (connection)

(async () => {
    for (let i = 0; i < 5; i++) {
        const p = await baro.readPressure();            // Read pressure, () → float Pa
        const t = await baro.readTemperature();         // Read temperature, () → float C (approximate)
        console.log(`${p.toFixed(0)} Pa, ${t.toFixed(1)} C`);
        await new Promise((r) => setTimeout(r, 1000));
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
