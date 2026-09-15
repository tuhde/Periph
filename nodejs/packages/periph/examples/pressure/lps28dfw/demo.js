'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS28DFWFull } = require('../../../src/chips/pressure/lps28dfw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

(async () => {
    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    const lps = new LPS28DFWFull(connection);               // Create LPS28DFW driver, (connection)
    await lps.configure(LPS28DFWFull.ODR_25_HZ, LPS28DFWFull.AVG_64,
                        LPS28DFWFull.FS_MODE_1, true, LPS28DFWFull.LFPF_ODR_OVER_4);  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpfEn=true, lpfCfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    let samples = 0;
    for (let n = 0; n < 60; n++) {
        const vals = await lps.read();                      // Read both values, () → {pressure, temperature}
        const p = vals.pressure;
        const t = vals.temperature;
        const alt = 44330 * (1 - Math.pow(p / 1013.25, 1 / 5.255));
        const elapsed = (n + 1) * 0.5;
        console.log(`${elapsed.toFixed(1)}s  ${p.toFixed(2)} hPa  ${t.toFixed(2)} C  ${alt.toFixed(1)} m`);
        samples++;
        await new Promise(r => setTimeout(r, 500));
    }
    console.log(`Total samples: ${samples}`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();