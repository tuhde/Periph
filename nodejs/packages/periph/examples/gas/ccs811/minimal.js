'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CCS811Minimal } = require('../../../src/chips/gas/ccs811');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5A', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x5A) → Connection
const sensor = new CCS811Minimal(connection);                            // Create CCS811 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check HW_ID, start application, mode 1, () → void
    while (true) {
        const m = await sensor.read();                                   // Wait for the next sample, () → {eco2_ppm: number ppm, tvoc_ppb: number ppb}
        console.log('eCO2 %d ppm, TVOC %d ppb', m.eco2_ppm, m.tvoc_ppb);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
