'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RRH62000Minimal } = require('../../../src/chips/gas/rrh62000');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x69', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x69) → Connection
const sensor = new RRH62000Minimal(connection);                          // Create RRH62000 driver, (connection, {accessIntervalMs=1000}) → void

async function main() {
    await sensor.init();                                                 // Wait for start-up, check firmware version, () → void
    while (true) {
        const r = await sensor.read();                                   // Wait for a new sample, () → {pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3 µg/m³, temperature_c °C, humidity_rh %RH, tvoc µg/m³, eco2_ppm ppm, iaq}
        console.log('PM1 %s  PM2.5 %s  PM10 %s ug/m3  %s C  %s %%RH  TVOC %d ug/m3  eCO2 %d ppm  IAQ %s',
            r.pm1_ug_m3.toFixed(1), r.pm2_5_ug_m3.toFixed(1), r.pm10_ug_m3.toFixed(1), r.temperature_c.toFixed(2),
            r.humidity_rh.toFixed(2), r.tvoc, r.eco2_ppm, r.iaq.toFixed(2));
    }
}

main().catch(err => { console.error(err); process.exit(1); });
