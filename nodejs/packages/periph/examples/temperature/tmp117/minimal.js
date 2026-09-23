'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { TMP117Minimal } = require('periph/src/chips/temperature/tmp117');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, TMP117Minimal.I2C_ADDRESS);
    const sensor = new TMP117Minimal(connection);                                  // Create TMP117 driver, (connection)
    await sensor.init();                                                           // Confirm device identity, () → None

    for (let i = 0; i < 10; i++) {
        const t = await sensor.readTemperature();                                  // Read temperature, () → number °C
        console.log(`${t.toFixed(4)} °C`);
        await sleep(1000);
    }

    await connection.close();
})();
