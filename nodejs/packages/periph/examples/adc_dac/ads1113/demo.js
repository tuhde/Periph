'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1113Full } = require('periph/src/chips/adc_dac/ads1113');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1113Full.I2C_ADDRESS);
    const adc = new ADS1113Full(connection);                                        // Create ADS1113 Full driver, (connection)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    await adc.setDataRate(ADS1113Full.DR_8);                                        // Set data rate, (dr 0–7) → None

    // --- Log single-shot readings ---
    // Each readVoltage() wakes the chip, converts and powers down again.
    for (let i = 0; i < 5; i++) {
        const v = await adc.readVoltage();                                          // Read voltage, () → number V
        console.log(`${v.toFixed(5)} V`);
        await sleep(1000);
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    await adc.startContinuous();                                                    // Start continuous, () → None
    await sleep(200);
    let acc = 0;
    for (let i = 0; i < 10; i++) {
        acc += await adc.readContinuous();                                          // Read continuous, () → number V
        await sleep(125);
    }
    await adc.stopContinuous();                                                     // Stop continuous, () → None
    console.log(`average ${(acc / 10).toFixed(5)} V`);

    await connection.close();
})();
