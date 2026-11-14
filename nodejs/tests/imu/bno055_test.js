'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BNO055Full, BNO055Mode, BNO055Source } = require('../../packages/periph/src/chips/imu/bno055');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x29', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const norm = (v) => Math.sqrt(v.reduce((a, b) => a + b * b, 0));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new BNO055Full(connection);
    await imu.init();                       // rejects on a CHIP_ID mismatch

    checkTrue('chip_id', (await connection.readReg(0x00, 1))[0] === 0xA0);
    const info = await imu.revisions();
    checkTrue('acc_id', info.accId === 0xFB);
    checkTrue('mag_id', info.magId === 0x32);
    checkTrue('gyr_id', info.gyrId === 0x0F);
    checkTrue('mode_ndof', (await imu.getMode()) === 0x0C);

    const [h, r, p] = await imu.euler();
    checkTrue('euler range', h >= 0 && h < 6.5 && p > -3.2 && p < 3.2 && r > -1.6 && r < 1.6);
    checkTrue('quaternion unit', Math.abs(norm(await imu.quaternion()) - 1) < 0.05);
    const g = norm(await imu.gravity());
    checkTrue('gravity ~1 g', g > 8.5 && g < 11.0);
    checkTrue('linear acceleration small', norm(await imu.linearAcceleration()) < 5.0);
    const t = await imu.temperature();
    checkTrue('temperature range', t > -40 && t < 85);
    checkTrue('calibration status', (await imu.calibrationStatus()).every((v) => v >= 0 && v <= 3));
    const [status, error] = await imu.systemStatus();
    checkTrue('system status', status === 5 && error === 0);
    checkTrue('unique_id length', (await imu.uniqueId()).length === 16);
    checkTrue('calibration length', (await imu.getCalibration()).length === 22);

    await imu.setMode(BNO055Mode.AMG);
    checkTrue('mode_amg', (await imu.getMode()) === 0x07);
    const a = norm(await imu.acceleration());
    checkTrue('accel magnitude ~1 g', a > 8.0 && a < 11.5);
    const m = norm(await imu.magneticField());
    checkTrue('mag field range', m > 5.0 && m < 200.0);
    await imu.setMode(BNO055Mode.NDOF);
    checkTrue('mode_ndof_again', (await imu.getMode()) === 0x0C);

    const st = await imu.selfTest();
    checkTrue('self_test passes', st.acc && st.mag && st.gyr && st.mcu);
    checkTrue('mode after self_test', (await imu.getMode()) === 0x0C);

    await imu.enableInterrupt(BNO055Source.ACC_BSX_DRDY);
    await new Promise((res) => setTimeout(res, 50));
    const ints = await imu.pollInterrupt();
    checkTrue('poll_interrupt ok', ints >= 0 && ints <= 0xFF);
    await imu.disableInterrupt(BNO055Source.ACC_BSX_DRDY);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
