'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS28DFWFull } = require('../../../src/chips/pressure/lps28dfw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lps = new LPS28DFWFull(connection);                   // Create LPS28DFW driver, (connection)

(async () => {
    const cid = await lps.chipId();                         // Read chip ID, () → number
                                                            // returns 0xB4 for LPS28DFW
    await lps.configure(LPS28DFWFull.ODR_25_HZ, LPS28DFWFull.AVG_64,
                        LPS28DFWFull.FS_MODE_1, true, LPS28DFWFull.LFPF_ODR_OVER_4);  // Configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpfEn bool, lpfCfg 0/1) → void
                                                            // sets output data rate, averaging, full-scale, IIR filter
    await lps.setThreshold(1050.0, true, true);             // Set pressure threshold, (thresholdHpa, high, low) → void
                                                            // arms PH/PL when pressure crosses thresholdHpa
    await lps.setOffset(0.5);                               // Set one-point calibration, (offsetHpa) → void
                                                            // subtracts 0.5 hPa from subsequent readings
    const ready = await lps.isDataReady();                  // Check data ready, () → bool
                                                            // reads STATUS.P_DA
    const vals = await lps.read();                          // Read both values, () → {pressure, temperature}
                                                            // burst-reads pressure+temperature
    await lps.softreset();                                  // Soft reset, () → void
                                                            // waits ~2 ms for reboot
    await lps.fifoConfigure(LPS28DFWFull.FIFO_FIFO, 16, true);  // Configure FIFO, (mode 0–6, wtm 0–127, stopOnWtm bool) → void
                                                            // enables 16-sample watermark FIFO
    const level = await lps.fifoLevel();                    // FIFO unread count, () → number
    const samples = await lps.fifoRead(level);              // Drain FIFO, (count) → number[] hPa
    const os = await lps.readOneshot();                     // One-shot read, () → {pressure, temperature}
                                                            // triggers a single measurement with ODR=0
    console.log(`chip=0x${cid.toString(16)}, ready=${ready}, vals=${JSON.stringify(vals)}, ` +
                `fifo=${level}, samples=${samples.length}, os=${JSON.stringify(os)}`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();