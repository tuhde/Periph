'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BME280Minimal } = require('../../../src/chips/environmental/bme280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bme = new BME280Minimal(connection);              // Create BME280 driver, (connection, busType='i2c')

(async () => {
    for (let i = 0; i < 5; i++) {
        const t = await bme.temperature();             // Read temperature, () → number °C
        const p = await bme.pressure();                // Read pressure, () → number hPa
        const h = await bme.humidity();                // Read humidity, () → number %RH
        console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa, ${h.toFixed(1)} %RH`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
