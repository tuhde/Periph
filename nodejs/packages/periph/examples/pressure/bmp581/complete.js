'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP581Full } = require('../../../src/chips/pressure/bmp581');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x46', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bmp = new BMP581Full(connection);                 // Create BMP581 driver, (connection, busType='i2c')

(async () => {
    const cid = await bmp.chipId();                     // Read chip ID, () → number
    console.log(`chip_id=${cid} (expect 0x50)`);

    await bmp.configure(0x1C, BMP581Full.OSR_1X, BMP581Full.OSR_1X, true);  // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en) → Promise<void>
    await bmp.setMode(BMP581Full.MODE_NORMAL);          // Set power mode, (mode 0/1/2/3) → Promise<void>
    await bmp.setIirFilter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS);  // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → Promise<void>
    await bmp.configureFifo(BMP581Full.FIFO_BOTH, BMP581Full.FIFO_STREAM, 8);  // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → Promise<void>
    const n = await bmp.fifoCount();                    // Read FIFO frame count, () → number
    await bmp.enableDrdyInterrupt(true);                // Enable data-ready interrupt, (enable) → Promise<void>
    const drdy = await bmp.dataReady();                 // Check data ready, () → Promise<boolean>
    const forced = await bmp.forced();                  // Trigger FORCED measurement, () → Promise<{pressure,temperature}>
    const both = await bmp.both();                      // Read both atomically, () → Promise<{pressure,temperature}>
    const alt = await bmp.altitude();                   // Compute altitude, (sea_level_pa=101325) → Promise<number>
    const st = await bmp.status();                      // Read STATUS, () → number
    const ist = await bmp.interruptStatus();            // Read INT_STATUS, () → number
    const eff = await bmp.effectiveOsr();               // Read effective OSR, () → Promise<{osrP,osrT}>
    await bmp.setOorThreshold(110000, 200, 1);          // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → Promise<void>
    await bmp.softwareReset();                          // Soft reset chip, () → Promise<void>

    console.log(`P=${both.pressure.toFixed(1)} Pa, T=${both.temperature.toFixed(2)} C, alt=${alt.toFixed(1)} m, frames=${n}, drdy=${drdy}, eff=${JSON.stringify(eff)}`);
    console.log('===DONE: 0 passed, 0 failed===');
    await connection.close();
})();