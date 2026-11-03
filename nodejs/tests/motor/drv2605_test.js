'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { DRV2605Minimal, DRV2605Full } = require('../../packages/periph/src/chips/motor/drv2605');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5A', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const minimal = new DRV2605Minimal(connection);
    await minimal.init();
    checkTrue('init', true);
    await minimal.playEffect(1);
    await sleep(500);
    checkTrue('effect_finishes', !(await minimal.isPlaying()));

    const haptic = new DRV2605Full(connection);
    await haptic.init();
    checkTrue('device_id', (await haptic.readDeviceId()) === 3);
    await haptic.playSequence([1, ['wait', 100], 1]);
    checkTrue('sequence_finishes', await haptic.waitDone(3000));

    await haptic.rtpLevel(0.5);
    await sleep(100);
    const vdd = await haptic.readSupplyVoltage();
    checkTrue('supply_voltage_plausible', vdd > 2.0 && vdd < 6.0);
    await haptic.stop();
    checkTrue('stop_clears_go', !(await haptic.isPlaying()));

    checkTrue('diagnose_runs', typeof (await haptic.diagnose()) === 'boolean');
    const cal = await haptic.calibrate(false, 3.0, 3.0);
    checkTrue('calibrate_runs', typeof cal.ok === 'boolean');
    checkTrue('calibrate_completes', !(await haptic.isPlaying()));
    const status = await haptic.readStatus();
    checkTrue('no_overtemp', !status.overTemp);
    checkTrue('no_overcurrent', !status.overCurrent);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
