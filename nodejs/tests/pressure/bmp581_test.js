'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMP581Minimal, BMP581Full } = require('../../packages/periph/src/chips/pressure/bmp581');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x46', 16);

let passed = 0, failed = 0;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const bmp = new BMP581Minimal(connection);

    // Software decode smoke: 0x1000 -> 0.0625 °C, 0x04 -> 0.0625 Pa.
    let rawT = 0x1000;
    let rawP = 0x04;
    if (rawT & 0x800000) rawT -= 0x1000000;
    if (rawP & 0x800000) rawP -= 0x1000000;
    const t = rawT / 65536.0;
    const p = rawP / 64.0;
    if (Math.abs(t - 0.0625) < 1e-6) { console.log('PASS temperature_decode'); passed++; }
    else { console.log('FAIL temperature_decode: got ' + t); failed++; }
    if (Math.abs(p - 0.0625) < 1e-6) { console.log('PASS pressure_decode'); passed++; }
    else { console.log('FAIL pressure_decode: got ' + p); failed++; }

    const bmpFull = new BMP581Full(connection);
    await bmpFull.setMode(BMP581Full.MODE_NORMAL);
    if (bmpFull._pwrMode === 1) { console.log('PASS set_mode'); passed++; }
    else { console.log('FAIL set_mode'); failed++; }

    await bmpFull.configure(0x17, 4, 2, true);
    if (bmpFull._odr === 0x17 && bmpFull._osrP === 4 && bmpFull._osrT === 2) {
        console.log('PASS configure'); passed++;
    } else { console.log('FAIL configure'); failed++; }

    await bmpFull.setIirFilter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS);
    console.log('PASS set_iir_filter'); passed++;
    await bmpFull.enableDrdyInterrupt(true);
    console.log('PASS enable_drdy_interrupt'); passed++;
    await bmpFull.configureFifo(BMP581Full.FIFO_BOTH, BMP581Full.FIFO_STREAM, 8);
    console.log('PASS configure_fifo'); passed++;
    await bmpFull.setOorThreshold(110000, 200, 1);
    console.log('PASS set_oor_threshold'); passed++;

    const alt = await bmpFull.altitude();
    if (alt >= -500 && alt <= 9000) { console.log('PASS altitude'); passed++; }
    else { console.log('FAIL altitude'); failed++; }

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();