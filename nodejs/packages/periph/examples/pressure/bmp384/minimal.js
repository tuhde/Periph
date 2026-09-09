'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP384Minimal } = require('../../../src/chips/pressure/bmp384');

async function main() {
    const connection = new I2CConnection(1, 0x76);
    const bmp = new BMP384Minimal(connection);                  // Create BMP384 driver, (connection, busType='i2c')

    for (let i = 0; i < 5; i++) {
        const t = await bmp.temperature();                      // Read temperature, () → float °C
        const p = await bmp.pressure();                         // Read pressure, () → float hPa
        console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa`);
        await new Promise((r) => setTimeout(r, 1000));
    }
    await connection.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
