'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1113Full } = require('periph/src/chips/adc_dac/ads1113');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1113Full.I2C_ADDRESS);
    const adc = new ADS1113Full(connection);                                        // Create ADS1113 Full driver, (connection)
                                                                                    // no I²C traffic; Config word is rebuilt per conversion
    const raw = await adc.readRaw();                                                // Read raw code, () → number
                                                                                    // single-shot conversion, signed 16-bit
    const v = await adc.readVoltage();                                              // Read voltage, () → number V
                                                                                    // raw × lsb()
    console.log(`raw ${raw} voltage ${v.toFixed(5)} V`);
    const fs = adc.fullScale();                                                     // Full scale, () → number V
                                                                                    // fixed ±2.048 V
    const lsb = adc.lsb();                                                          // LSB, () → number V
                                                                                    // fullScale() / 32768
    console.log(`full scale ${fs} V, lsb ${lsb} V`);
    await adc.setDataRate(ADS1113Full.DR_250);                                      // Set data rate, (dr 0–7) → None
                                                                                    // DR field of the Config word
    const sps = adc.dataRateSps();                                                  // Data rate, () → number SPS
                                                                                    // samples per second of the current setting
    console.log(`rate ${adc.getDataRate()} = ${sps} SPS`);
    await adc.startContinuous();                                                    // Start continuous, () → None
                                                                                    // MODE ← 0
    await sleep(50);
    const craw = await adc.readContinuousRaw();                                     // Read continuous raw, () → number
                                                                                    // CONVERSION without touching Config
    const cv = await adc.readContinuous();                                          // Read continuous, () → number V
                                                                                    // readContinuousRaw() × lsb()
    console.log(`continuous ${craw} ${cv.toFixed(5)} V`);
    await adc.stopContinuous();                                                     // Stop continuous, () → None
                                                                                    // MODE ← 1
    const busy = await adc.isConverting();                                          // Check conversion, () → boolean
                                                                                    // CONFIG.OS == 0
    const cfg = await adc.readConfig();                                             // Read CONFIG, () → number
                                                                                    // raw 16-bit register
    console.log(`converting ${busy}, config 0x${cfg.toString(16)}`);
    await adc.reset();                                                              // Reset, () → None
                                                                                    // writes the reset values back

    await connection.close();
})();
