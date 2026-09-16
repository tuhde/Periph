'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMP085Minimal, BMP085Full } = require('../../packages/periph/src/chips/pressure/bmp085');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x77', 16);

let passed = 0, failed = 0;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const bmp = new BMP085Minimal(connection);

    bmp._oss = 0;
    bmp._b5 = 0;
    bmp._ac1 = 408;
    bmp._ac2 = -72;
    bmp._ac3 = -14383;
    bmp._ac4 = 32741;
    bmp._ac5 = 32757;
    bmp._ac6 = 23153;
    bmp._b1 = 6190;
    bmp._b2 = 4;
    bmp._mc = -8711;
    bmp._md = 2868;

    const b5 = bmp._compensateTemp(27898);
    if (b5 !== 0) { console.log('PASS temp_compensation_b5'); passed++; }
    else          { console.log('FAIL temp_compensation_b5'); failed++; }

    const p_pa = bmp._compensatePressure(23843);
    if (Math.abs(p_pa - 69964) < 1) { console.log('PASS pressure_compensation'); passed++; }
    else                            { console.log('FAIL pressure_compensation'); failed++; }

    const bmpFull = new BMP085Full(connection, 0);
    if (bmpFull.oversampling() === 0) { console.log('PASS default_oss'); passed++; }
    else                             { console.log('FAIL default_oss'); failed++; }

    bmpFull.setOversampling(2);
    if (bmpFull.oversampling() === 2) { console.log('PASS set_oss'); passed++; }
    else                              { console.log('FAIL set_oss'); failed++; }

    const alt = await bmpFull.altitude();
    if (alt >= 0) { console.log('PASS altitude'); passed++; }
    else          { console.log('FAIL altitude'); failed++; }

    const slp = await bmpFull.seaLevelPressure(0);
    if (slp >= 90000 && slp <= 110000) { console.log('PASS sea_level_pressure'); passed++; }
    else                                { console.log('FAIL sea_level_pressure'); failed++; }

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main();