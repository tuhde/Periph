'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP581Full } = require('../../../src/chips/pressure/bmp581');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x46', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

(async () => {
    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    const bmp = new BMP581Full(connection);             // Create BMP581 driver, (connection, busType='i2c')
    await bmp.configure(0x17, BMP581Full.OSR_16X, BMP581Full.OSR_4X, true);  // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → Promise<void>

    const pressures = [], temps = [], alts = [];
    for (let n = 0; n < 300; n++) {
        const p = await bmp.pressure();                  // Read pressure, () → number Pa
        const t = await bmp.temperature();               // Read temperature, () → number °C
        const a = await bmp.altitude();                  // Compute altitude, (sea_level_pa=101325) → number m
        if (n % 10 === 0) {
            const start = Math.max(0, n - 10);
            const span = Math.min(10, n);
            let mp = 0, mt = 0, ma = 0;
            for (let k = start; k < n; k++) {
                mp += pressures[k];
                mt += temps[k];
                ma += alts[k];
            }
            if (span > 0) { mp /= span; mt; ma /= span; }
            console.log(`${(n / 10)}0s: rolling P=${mp.toFixed(1)} Pa, T=${(mt / span).toFixed(2)} C, alt=${(ma / span).toFixed(2)} m`);
        }
        pressures.push(p);
        temps.push(t);
        alts.push(a);
    }

    const amin = Math.min(...alts), amax = Math.max(...alts);
    console.log(`Bypass: alt min=${amin.toFixed(3)} max=${amax.toFixed(3)} spread=${(amax - amin).toFixed(3)} m`);

    await bmp.setIirFilter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS);  // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → Promise<void>

    const alts2 = [];
    for (let n = 0; n < 300; n++) {
        await bmp.pressure();                            // Read pressure, () → number Pa
        alts2.push(await bmp.altitude());                // Compute altitude, (sea_level_pa=101325) → number m
    }
    const amin2 = Math.min(...alts2), amax2 = Math.max(...alts2);
    console.log(`IIR=3:  alt min=${amin2.toFixed(3)} max=${amax2.toFixed(3)} spread=${(amax2 - amin2).toFixed(3)} m`);

    const pmin = Math.min(...pressures), pmax = Math.max(...pressures);
    const psum = pressures.reduce((s, v) => s + v, 0);
    console.log(`Min P=${pmin.toFixed(1)}, max P=${pmax.toFixed(1)}, mean P=${(psum / pressures.length).toFixed(1)} Pa`);

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();