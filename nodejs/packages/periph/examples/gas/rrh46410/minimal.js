'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RRH46410Minimal } = require('../../../src/chips/gas/rrh46410');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x38) → Connection
const sensor = new RRH46410Minimal(connection);                          // Create RRH46410 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → void
    while (true) {
        const r = await sensor.read();                                   // Wait for the next sample, () → {iaq, tvoc_mg_m3 mg/m³, etoh_ppm ppm, eco2_ppm ppm, relative_iaq, sample, warmed_up}
        console.log('IAQ %s  TVOC %s mg/m3  EtOH %s ppm  eCO2 %d ppm  %s', r.iaq.toFixed(1), r.tvoc_mg_m3.toFixed(2),
            r.etoh_ppm.toFixed(2), r.eco2_ppm, r.warmed_up ? 'ok' : 'warming up');
    }
}

main().catch(err => { console.error(err); process.exit(1); });
