'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMP384Minimal, BMP384Full } = require('../../packages/periph/src/chips/pressure/bmp384');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x76', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

    const bmp = new BMP384Minimal(connection);

    if (bmp._parT1 > 0) {
        console.log('PASS calibration_loaded'); passed++;
    } else {
        console.log('FAIL calibration_loaded: parT1 =', bmp._parT1); failed++;
    }

    if (bmp._osrP === 4 && bmp._osrT === 1) {
        console.log('PASS default_oversampling'); passed++;
    } else {
        console.log('FAIL default_oversampling: osrP=' + bmp._osrP + ' osrT=' + bmp._osrT); failed++;
    }

    if (bmp._iir === 2) {
        console.log('PASS default_iir'); passed++;
    } else {
        console.log('FAIL default_iir:', bmp._iir); failed++;
    }

    const t = await bmp.temperature();
    checkTrue('temperature_in_range', t >= -40 && t <= 85);

    const p = await bmp.pressure();
    checkTrue('pressure_in_range', p >= 300 && p <= 1250);

    const bmpFull = new BMP384Full(connection);
    if (typeof (await bmpFull.isDataReady()) === 'boolean') {
        console.log('PASS is_data_ready'); passed++;
    } else {
        console.log('FAIL is_data_ready'); failed++;
    }

    await bmpFull.configure(2, 1, 1, 0x04);
    if (bmpFull._osrP === 2 && bmpFull._iir === 1 && bmpFull._odr === 0x04) {
        console.log('PASS configure_writes_through'); passed++;
    } else {
        console.log('FAIL configure_writes_through: osrP=' + bmpFull._osrP + ' iir=' + bmpFull._iir + ' odr=' + bmpFull._odr); failed++;
    }

    await bmpFull.fifoConfigure(true, true, 10);
    const frames = await bmpFull.fifoRead();
    if (Array.isArray(frames)) {
        console.log('PASS fifo_read_returns_list'); passed++;
    } else {
        console.log('FAIL fifo_read_returns_list:', typeof frames); failed++;
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    await connection.close();
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
