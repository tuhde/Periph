'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS7828Minimal } = require('periph/src/chips/adc_dac/ads7828');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS7828Minimal.I2C_ADDRESS);   // Create I2C connection, (bus, address)
    const adc = new ADS7828Minimal(connection);                             // Create ADS7828 driver, (connection, vref=null → internal 2.5 V)
    await adc.init();                                                       // Enable internal reference, () → None

    const raw = await adc.readRaw(0);                                       // Read CH0 raw code, (channel 0–7) → int
    const volts = await adc.readVoltage(0);                                 // Read CH0 voltage, (channel 0–7) → float V
    const all = await adc.readAll();                                        // Read all eight channels, () → float[8] V
    console.log(raw, volts, all);
    await connection.close();
})();
