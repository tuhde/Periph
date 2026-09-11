'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS22DFFull } = require('../../../src/chips/pressure/lps22df');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

(async () => {
    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
    // 4-sample averaging trims noise without adding visible lag.
    const lps = new LPS22DFFull(connection);                 // Create LPS22DF driver, (connection, busType='i2c')
    await lps.configure(4, 0, true, 1, true);                // Configure chip, (odr=25 Hz, avg=4, enLpfp=true, lfpfCfg=ODR/9, bdu=true) → undefined

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    // LPS22DF reports absolute pressure; relative altitude is what matters indoors.
    await new Promise(r => setTimeout(r, 2000));
    const baseline_p = await lps.pressure();                 // Read pressure, () → number Pa
    console.log(`Baseline: ${baseline_p.toFixed(0)} Pa`);

    const pressures = [], temps = [], deltas = [];
    for (let n = 0; n < 30; n++) {
        const p = await lps.pressure();                       // Read pressure, () → number Pa
        const t = await lps.temperature();                    // Read temperature, () → number °C
        const d = await lps.altitude(baseline_p);             // Compute altitude, (seaLevelPa=baseline_p) → number m
                                                              // delta altitude in metres from the baseline
        pressures.push(p);
        temps.push(t);
        deltas.push(d);
        console.log(`${n}s: ${p.toFixed(0)} Pa, T=${t.toFixed(2)} C, Δalt=${d.toFixed(3)} m`);
        await new Promise(r => setTimeout(r, 1000));
    }
    const mean = a => a.reduce((x, y) => x + y, 0) / a.length;
    console.log(`P min=${Math.min(...pressures).toFixed(0)} max=${Math.max(...pressures).toFixed(0)} mean=${mean(pressures).toFixed(1)} Pa`);
    console.log(`T min=${Math.min(...temps).toFixed(2)} max=${Math.max(...temps).toFixed(2)} mean=${mean(temps).toFixed(2)} C`);
    console.log(`Δalt min=${Math.min(...deltas).toFixed(3)} max=${Math.max(...deltas).toFixed(3)} mean=${mean(deltas).toFixed(3)} m`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();