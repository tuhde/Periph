'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { MPL115A2Full } = require('../../../src/chips/pressure/mpl115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const baro = new MPL115A2Full(connection);              // Create MPL115A2 driver, (connection)

(async () => {
    console.log('Coefficients:', await baro.coefficients()); // Read cached coefficients, () → {a0, b1, b2, c12}

    // --- Barometer log ---
    // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
    // altitude and the raw counts, then the min/max of the run.
    const values = [];
    for (let n = 0; n < 15; n++) {
        const pa = await baro.readPressure();           // Read pressure, () → float Pa
        const hpa = await baro.pressureHpa();           // Read pressure, () → float hPa
        const alt = await baro.altitude();              // Compute altitude, (seaLevelPa=101325) → float m
        const raw = await baro.readRaw();               // One conversion, () → {padc, tadc}
        values.push(pa);
        console.log(`${n * 2}s: ${pa.toFixed(0)} Pa, ${hpa.toFixed(2)} hPa, alt=${alt.toFixed(1)} m, raw=(${raw.padc}, ${raw.tadc})`);
        await new Promise((r) => setTimeout(r, 2000));
    }
    console.log(`Summary: min=${Math.min(...values).toFixed(0)} Pa, max=${Math.max(...values).toFixed(0)} Pa`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
