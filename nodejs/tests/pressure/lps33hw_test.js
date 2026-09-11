'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LPS33HWMinimal, LPS33HWFull } = require('../../packages/periph/src/chips/pressure/lps33hw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

let passed = 0, failed = 0;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const lps = new LPS33HWMinimal(connection);

    const cid = await lps._readReg(0x0F, 1);
    if (cid[0] === 0xB1) { console.log('PASS chip_id'); passed++; }
    else { console.log('FAIL chip_id: got 0x' + cid[0].toString(16)); failed++; }

    const t = await lps.temperature();
    if (t > -50 && t < 100) { console.log('PASS temperature_in_range'); passed++; }
    else { console.log('FAIL temperature_in_range: got ' + t); failed++; }

    const p = await lps.pressure();
    if (p > 80000 && p < 120000) { console.log('PASS pressure_in_range'); passed++; }
    else { console.log('FAIL pressure_in_range: got ' + p); failed++; }

    const lpsFull = new LPS33HWFull(connection);
    await lpsFull.configure(LPS33HWFull.ODR_10_HZ, 1, 1, LPS33HWFull.LPFP_BW_ODR_20, 0, 0);
    const sample = await lpsFull.oneShot();
    if (sample.pressure_Pa > 80000 && sample.pressure_Pa < 120000
        && sample.temperature_C > -50 && sample.temperature_C < 100) {
        console.log('PASS one_shot');
        passed++;
    } else {
        console.log('FAIL one_shot: got ' + JSON.stringify(sample));
        failed++;
    }

    const alt = 44330 * (1 - Math.pow(sample.pressure_Pa / 101325, 1 / 5.255));
    if (alt > -500 && alt < 9000) { console.log('PASS altitude_barometric'); passed++; }
    else { console.log('FAIL altitude_barometric: got ' + alt); failed++; }

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main();
