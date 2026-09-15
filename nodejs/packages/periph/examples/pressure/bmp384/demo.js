'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP384Full }    = require('../../../src/chips/pressure/bmp384');

async function main() {
    const connection = new I2CConnection(1, 0x76);
    const bmp = new BMP384Full(connection);                     // Create BMP384 driver, (connection, busType='i2c')

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    await bmp.configure(4, 1, 2, 0x03);                        // Configure ADC and IIR filter, (osrP 0–5, osrT 0–5, iirFilter 0–7, odrSel 0x00–0x11) → Promise<void>
    await bmp.setMode(BMP384Full.MODE_NORMAL);                  // Set power mode, (mode 0/1/3) → Promise<void>

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const SEA_LEVEL_HPA = 1013.25;
    const start = Date.now();
    let next = start;
    let rows = 0;
    while (Date.now() - start < 30000) {
        const now = Date.now();
        if (now >= next) {
            const t = await bmp.temperature();                  // Read temperature, () → Promise<float> °C
            const p = await bmp.pressure();                     // Read pressure, () → Promise<float> hPa
            const altitude = 44330 * (1 - Math.pow(p / SEA_LEVEL_HPA, 1 / 5.255));
            const elapsed = (now - start) / 1000;
            console.log(`${elapsed.toFixed(1)}s  ${p.toFixed(2)} hPa  ${t.toFixed(1)} C  ${altitude.toFixed(1)} m`);
            rows++;
            next += 500;
        }
        await new Promise((r) => setTimeout(r, 50));
    }

    console.log(`Sampled ${rows} rows over 30 s`);
    await connection.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
