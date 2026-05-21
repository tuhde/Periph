'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP280Full } = require('../../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP280Full(transport);                // Create BMP280 driver, (transport, addr=0x76, osrs_t=1, osrs_p=1, mode=1, filter=0, t_sb=0)

const startMs = Date.now();

// --- Weather monitoring preset: forced mode, ×1/×1, filter off ---
// Lowest power: 2.7 µA at 1 Hz. Suitable for continuous weather logging.
bmp.configure(BMP280Full.OSRS_X1, BMP280Full.OSRS_X1,
              BMP280Full.MODE_FORCED, BMP280Full.FILTER_OFF,
              BMP280Full.T_SB_0_5_MS);               // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None

console.log('WEATHER-MONITORING  T[C]   P[hPa]   ALT[m]');

for (let n = 0; n < 30; n++) {
    const t = bmp.temperature();                      // Read temperature, () → float C
    const p = bmp.pressure();                         // Read pressure, () → float hPa
    const a = bmp.altitude();                         // Compute altitude, (sea_level_hpa=1013.25) → float m
    const elapsed = ((Date.now() - startMs) / 1000).toFixed(1);
    console.log(`${elapsed}s   ${t.toFixed(2)}   ${p.toFixed(2)}   ${a.toFixed(2)}`);
    if (n < 29) {
        const waitMs = 1000 - (Date.now() - startMs) % 1000;
        if (waitMs > 0) setTimeout(() => {}, waitMs);
    }
}

transport.close();
console.log('===DONE: 0 passed, 0 failed===');