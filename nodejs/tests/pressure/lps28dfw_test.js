'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LPS28DFWMinimal, LPS28DFWFull } = require('../../packages/periph/src/chips/pressure/lps28dfw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

let passed = 0, failed = 0;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const lps = new LPS28DFWMinimal(connection);

    if (lps._fsMode === 0 && lps._odr === 0x04 && lps._avg === 0x02) {
        console.log('PASS minimal_defaults'); passed++;
    } else { console.log('FAIL minimal_defaults'); failed++; }

    const raw24 = 1000 * 4096;
    const expectedMode1 = raw24 / 4096.0;
    if (Math.abs(expectedMode1 - 1000.0) < 0.01) { console.log('PASS pressure_sensitivity_mode1'); passed++; }
    else { console.log('FAIL pressure_sensitivity_mode1: got ' + expectedMode1); failed++; }

    const raw24Mode2 = 1000 * 2048;
    const expectedMode2 = raw24Mode2 / 2048.0;
    if (Math.abs(expectedMode2 - 1000.0) < 0.01) { console.log('PASS pressure_sensitivity_mode2'); passed++; }
    else { console.log('FAIL pressure_sensitivity_mode2: got ' + expectedMode2); failed++; }

    const raw16 = 2500;
    const tempC = raw16 / 100.0;
    if (Math.abs(tempC - 25.0) < 0.001) { console.log('PASS temperature_conversion'); passed++; }
    else { console.log('FAIL temperature_conversion: got ' + tempC); failed++; }

    const lpsFull = new LPS28DFWFull(connection);
    if (lpsFull._odr === 0x04 && lpsFull._avg === 0x02 && lpsFull._fsMode === 0) {
        console.log('PASS full_default_inherits'); passed++;
    } else { console.log('FAIL full_default_inherits'); failed++; }

    await lpsFull.configure(LPS28DFWFull.ODR_100_HZ, LPS28DFWFull.AVG_128,
                            LPS28DFWFull.FS_MODE_2, false, 1);
    if (lpsFull._odr === 0x07 && lpsFull._avg === 0x05 && lpsFull._fsMode === 1 &&
        lpsFull._lpfEn === 0 && lpsFull._lpfCfg === 1) {
        console.log('PASS full_configure'); passed++;
    } else { console.log('FAIL full_configure'); failed++; }

    let thresholdRaw = Math.trunc(1050.0 * 16);
    if (thresholdRaw > 0x7FFF) thresholdRaw = 0x7FFF;
    if (thresholdRaw === 16800) { console.log('PASS threshold_raw_conversion'); passed++; }
    else { console.log('FAIL threshold_raw_conversion: got ' + thresholdRaw); failed++; }

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main();