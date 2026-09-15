'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { L3G4200DFull } = require('../../../src/chips/gyroscope/l3g4200d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    const gyro = new L3G4200DFull(connection);            // Create L3G4200D driver, (connection, busType='i2c')
    await gyro.configure(1, 0, 500);                      // Configure chip, (odr=200Hz, bandwidth=0, full_scale=500) → undefined
    await gyro.enableHighpass(0, 4);                     // Enable high-pass, (mode=0, cutoff=4) → undefined
                                                          // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    await gyro.enableFifo(2, 10);                        // Enable FIFO, (mode=2=stream, watermark=10) → undefined

    const thresholdRadS = 90.0 * (Math.PI / 180.0);
    let alerts = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for (let n = 0; n < 50; n++) {
        while ((await gyro.fifoSamples()) < 10) {        // Read FIFO count, () → number
            await sleep(5);
        }
        const burst = await gyro.readFifo();              // Drain FIFO, () → [[x, y, z] rad/s, ...]
        if (!burst.length) continue;
        let mx = 0, my = 0, mz = 0;
        for (const s of burst) { mx += s[0]; my += s[1]; mz += s[2]; }
        mx /= burst.length; my /= burst.length; mz /= burst.length;
        if (Math.abs(mx) > thresholdRadS || Math.abs(my) > thresholdRadS || Math.abs(mz) > thresholdRadS) {
            alerts++;
            console.log(`ALERT  X=${mx.toFixed(2)} Y=${my.toFixed(2)} Z=${mz.toFixed(2)} rad/s`);
        } else {
            console.log(`       X=${mx.toFixed(2)} Y=${my.toFixed(2)} Z=${mz.toFixed(2)} rad/s`);
        }
        await sleep(20);
    }

    console.log(`Total alerts: ${alerts} / 50`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
