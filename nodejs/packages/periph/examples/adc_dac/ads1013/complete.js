'use strict';

// Exercises every method in the ADS1013 public API: single-shot reads, data
// rate, continuous conversion, config readback and reset.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1013Full } = require('periph/src/chips/adc_dac/ads1013');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1013Full.I2C_ADDRESS);
    const adc = new ADS1013Full(connection);                                       // Create ADS1013 Full driver, (connection)
                                                                                   // no I2C traffic; fixed ±2.048 V, 1600 SPS

    const raw = await adc.readRaw();                                               // Read raw code, () → number (−2048…2047)
                                                                                   // single-shot AIN0 − AIN1, polls OS until idle
    const v = await adc.readVoltage();                                             // Read voltage, () → number V
                                                                                   // raw × 0.001
    console.log('raw', raw, `voltage ${v.toFixed(4)} V`);
    console.log('full scale', adc.fullScale(), 'V');                               // Read full-scale range, () → number V
                                                                                   // always 2.048
    console.log('lsb', adc.lsb(), 'V');                                            // Read code size, () → number V
                                                                                   // always 0.001

    await adc.setDataRate(ADS1013Full.DR_250);                                     // Set data rate, (dr 0–6) → None
                                                                                   // DR_250 = 250 SPS
    console.log('dr code', adc.getDataRate());                                     // Read data-rate code, () → number
                                                                                   // cached DR[2:0]
    console.log('sps', adc.dataRateSps());                                         // Read data rate, () → number SPS
                                                                                   // table lookup of the DR code

    await adc.startContinuous();                                                   // Start continuous conversion, () → None
                                                                                   // MODE = 0 at the current data rate
    await sleep(20);
    console.log('continuous raw', await adc.readContinuousRaw());                  // Read latest code, () → number
                                                                                   // reads CONVERSION without touching Config
    console.log('continuous', (await adc.readContinuous()).toFixed(4), 'V');       // Read latest voltage, () → number V
                                                                                   // raw × lsb()
    await adc.stopContinuous();                                                    // Stop continuous conversion, () → None
                                                                                   // MODE = 1, back to single-shot
    console.log('converting', await adc.isConverting());                           // Check conversion in progress, () → boolean
                                                                                   // CONFIG.OS = 0 (single-shot only)
    console.log('config 0x' + (await adc.readConfig()).toString(16));              // Read CONFIG register, () → number
                                                                                   // raw 16-bit register
    await adc.reset();                                                             // Reset to power-up defaults, () → None
                                                                                   // rewrites CONFIG = 0x8583

    await connection.close();
})();
