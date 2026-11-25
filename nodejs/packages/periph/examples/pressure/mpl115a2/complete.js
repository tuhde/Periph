'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { MPL115A2Full } = require('../../../src/chips/pressure/mpl115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const baro = new MPL115A2Full(connection);              // Create MPL115A2 driver, (connection)

(async () => {
    const k = await baro.coefficients();                // Read cached coefficients, () → {a0, b1, b2, c12}
    await baro.reloadCoefficients();                    // Re-read coefficient block, () → void
                                                        // use after a power cycle
    const data = await baro.read();                     // One conversion, () → {pressure_pa, temperature_c}
    const raw = await baro.readRaw();                   // One conversion, () → {padc, tadc} 10-bit counts
    const p = await baro.compensate(raw.padc, raw.tadc); // Compensate raw counts, (padc, tadc) → float Pa
                                                        // pure function, no bus access
    const hpa = await baro.pressureHpa();               // Read pressure, () → float hPa
    const alt = await baro.altitude();                  // Compute altitude, (seaLevelPa=101325) → float m
                                                        // barometric formula, informative
    console.log('coef', k, data, raw, p, hpa, alt);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
