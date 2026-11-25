'use strict';

// Battery-voltage logger. The cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND) once per second in single-shot mode at 128 SPS,
// printing raw code, divider voltage and battery voltage plus a running
// minimum and maximum. The driver then switches to continuous mode at 3300
// SPS and prints the average of 100 samples.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1013Full } = require('periph/src/chips/adc_dac/ads1013');

const DIVIDER_RATIO = 2.0;
const LOG_SAMPLES = 10;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1013Full.I2C_ADDRESS);
    const adc = new ADS1013Full(connection);                                       // Create ADS1013 Full driver, (connection)

    // --- Slow, low-noise single-shot logging ---
    // 128 SPS gives the lowest noise; one conversion per second keeps the chip
    // in power-down in between.
    await adc.setDataRate(ADS1013Full.DR_128);                                     // Set data rate, (dr 0–6) → None
    let low = Infinity;
    let high = -Infinity;
    for (let i = 0; i < LOG_SAMPLES; i++) {
        const raw = await adc.readRaw();                                           // Read raw code, () → number (−2048…2047)
        const v = await adc.readVoltage();                                         // Read voltage, () → number V
        const battery = v * DIVIDER_RATIO;
        low = Math.min(low, battery);
        high = Math.max(high, battery);
        console.log(`raw ${raw} divider ${v.toFixed(3)} V battery ${battery.toFixed(3)} V (min ${low.toFixed(3)}, max ${high.toFixed(3)})`);
        await sleep(1000);
    }

    // --- Fast continuous averaging ---
    // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
    await adc.setDataRate(ADS1013Full.DR_3300);                                    // Set data rate, (dr 0–6) → None
    await adc.startContinuous();                                                   // Start continuous conversion, () → None
    await sleep(10);
    let total = 0;
    for (let i = 0; i < 100; i++) {
        total += await adc.readContinuous();                                       // Read latest voltage, () → number V
        await sleep(1);
    }
    await adc.stopContinuous();                                                    // Stop continuous conversion, () → None
    console.log(`average battery ${(total / 100 * DIVIDER_RATIO).toFixed(3)} V`);

    await connection.close();
})();
