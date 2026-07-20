'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP280Full } = require('../../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

// --- Weather monitoring preset: lowest power, forced mode ---
// BMP280 datasheet Table 7: ×1/×1, filter off, forced mode.
// One sample per second for 30 seconds.
const bmp = new BMP280Full(transport);                   // Create BMP280 driver, (transport, busType='i2c')
bmp.configure(BMP280Full.OSRS_X1, BMP280Full.OSRS_X1, BMP280Full.MODE_FORCED, BMP280Full.FILTER_OFF, BMP280Full.T_SB_0_5_MS);  // Configure chip, (osrsT=×1, osrsP=×1, mode=forced, filter=off, tSb=0) → undefined

const temps = [], pressures = [], alts = [];
for (let n = 0; n < 30; n++) {
    const t = bmp.temperature();                         // Read temperature, () → number °C
    const p = bmp.pressure();                           // Read pressure, () → number hPa
    const a = bmp.altitude();                          // Compute altitude, (seaLevelHpa=1013.25) → number m
    temps.push(t);
    pressures.push(p);
    alts.push(a);
    console.log(`${n}s: ${t.toFixed(1)} C, ${p.toFixed(1)} hPa, alt=${a.toFixed(1)} m`);
}

const avgT = temps.reduce((s, v) => s + v, 0) / temps.length;
const avgP = pressures.reduce((s, v) => s + v, 0) / pressures.length;
console.log(`Weather: T=${Math.min(...temps).toFixed(1)}/${avgT.toFixed(1)}/${Math.max(...temps).toFixed(1)} C, P=${Math.min(...pressures).toFixed(1)}/${avgP.toFixed(1)}/${Math.max(...pressures).toFixed(1)} hPa`);

// --- Indoor navigation preset: high resolution with IIR filter ---
// ×16/×2, filter coefficient 16, normal mode at ~26 Hz.
bmp.configure(BMP280Full.OSRS_X2, BMP280Full.OSRS_X16, BMP280Full.MODE_NORMAL, BMP280Full.FILTER_16, BMP280Full.T_SB_0_5_MS);  // Configure chip, (osrsT=×2, osrsP=×16, mode=normal, filter=16, tSb=0.5ms) → undefined

const alts2 = [];
for (let n = 0; n < 30; n++) {
    const t = bmp.temperature();                         // Read temperature, () → number °C
    const p = bmp.pressure();                           // Read pressure, () → number hPa
    const a = bmp.altitude();                          // Compute altitude, (seaLevelHpa=1013.25) → number m
    alts2.push(a);
    console.log(`${n}s: alt=${a.toFixed(4)} m`);
}

const meanAlt = alts2.reduce((s, v) => s + v, 0) / alts2.length;
const variance = alts2.reduce((s, v) => s + (v - meanAlt) ** 2, 0) / alts2.length;
const std = Math.sqrt(variance);
console.log(`Navigation: alt min=${Math.min(...alts2).toFixed(4)} max=${Math.max(...alts2).toFixed(4)} std=${std.toFixed(4)} m`);

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
