'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1114Minimal } = require('periph/src/chips/adc_dac/ads1114');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1114Minimal.I2C_ADDRESS);
    const adc = new ADS1114Minimal(connection);                                     // Create ADS1114 driver, (connection)

    for (let i = 0; i < 10; i++) {
        const v = await adc.readVoltage();                                          // Read voltage, () → number V
        console.log(`${v.toFixed(5)} V`);
        await sleep(1000);
    }

    await connection.close();
})();
