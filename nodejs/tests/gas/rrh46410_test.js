'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { RRH46410Full } = require('../../packages/periph/src/chips/gas/rrh46410');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x38', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new RRH46410Full(connection);
    await sensor.init();
    const id = await sensor.productId();
    checkTrue('product_id', id.productId === 0x2310);
    checkTrue('firmware_version', id.firmware.length === 3);
    checkTrue('tracking_number', (await sensor.trackingNumber()).length === 6);
    checkTrue('mode_iaq', (await sensor.getMode()) === RRH46410Full.MODE_IAQ_2ND_GEN);
    await sensor.setHumidity(50);
    checkTrue('status_ok', sensor.status().raw === 0);
    let r = await sensor.read();
    checkTrue('read_iaq_range', r.iaq >= 0 && r.iaq <= 25.5);
    checkTrue('read_tvoc_range', r.tvoc_mg_m3 >= 0 && r.tvoc_mg_m3 <= 655.35);
    checkTrue('read_eco2_range', r.eco2_ppm >= 0 && r.eco2_ppm <= 0xFFFF);
    checkTrue('read_not_warmed_up', r.warmed_up === false);
    checkTrue('wait_for_sample', await sensor.waitForSample(5.0));
    checkTrue('read_results', (await sensor.readResults()) !== null);
    checkTrue('poll_interrupt_false', (await sensor.pollInterrupt()) === false);
    const hits = [];
    await sensor.onInterrupt((res) => hits.push(res));
    await sleep(7000);
    await sensor.offInterrupt();
    checkTrue('on_interrupt', hits.length >= 1);
    await sensor.setMode(RRH46410Full.MODE_PBAQ);
    checkTrue('mode_pbaq', (await sensor.getMode()) === RRH46410Full.MODE_PBAQ);
    r = await sensor.read();
    checkTrue('read_pbaq', 'tvoc_mg_m3' in r && !('iaq' in r));
    await sensor.configGpio(0x03);
    await sensor.setGpio(0x01);
    checkTrue('set_gpio', ((await sensor.getGpio()) & 0x01) === 1);
    await sensor.clearGpio(0x01);
    checkTrue('clear_gpio', ((await sensor.getGpio()) & 0x01) === 0);
    checkTrue('send_command', (await sensor.sendCommand(0x10, [], 1))[0] === RRH46410Full.MODE_PBAQ);
    await sensor.suspend();
    checkTrue('suspend', (await sensor.getMode()) === RRH46410Full.MODE_SUSPEND);
    await sensor.reset();
    checkTrue('reset_suspend', (await sensor.getMode()) === RRH46410Full.MODE_SUSPEND);

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
