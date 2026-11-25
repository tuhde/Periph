'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1014Minimal } = require('periph/src/chips/adc_dac/ads1014');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1014Minimal.I2C_ADDRESS);
    const adc = new ADS1014Minimal(connection);                                    // Create ADS1014 driver, (connection)

    for (let i = 0; i < 10; i++) {
        const raw = await adc.readRaw();                                           // Read raw code, () → number (−2048…2047)
        const v = await adc.readVoltage();                                         // Read voltage, () → number V
        console.log(`${raw} -> ${v.toFixed(3)} V`);
        await sleep(1000);
    }

    await connection.close();
})();
