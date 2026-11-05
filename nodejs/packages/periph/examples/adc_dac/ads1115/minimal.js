'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1115Minimal } = require('periph/src/chips/adc_dac/ads1115');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1115Minimal.I2C_ADDRESS);
    const adc = new ADS1115Minimal(connection);                                     // Create ADS1115 driver, (connection)

    for (let i = 0; i < 10; i++) {
        const v = await adc.readVoltage(0);                                         // Read voltage, (channel=0) → number V
        console.log(`${v.toFixed(5)} V`);
        await sleep(1000);
    }

    await connection.close();
})();
