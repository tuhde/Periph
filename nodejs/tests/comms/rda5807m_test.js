'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { RDA5807MFull } = require('../../packages/periph/src/chips/comms/rda5807m');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x10', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

function sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

// FM_READY deasserts on any register write and takes ~20 ms to settle back;
// not documented in the datasheet, measured on real hardware.
const SETTLE_MS = 30;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const fm = new RDA5807MFull(connection, 100.0, 8);

    sleep(SETTLE_MS);
    checkTrue('is_ready', await fm.isReady());

    let f = await fm.frequency();
    checkTrue('frequency near 100.0 MHz', Math.abs(f - 100.0) < 0.2);

    await fm.setFrequency(97.5);
    f = await fm.frequency();
    checkTrue('set_frequency: frequency near 97.5 MHz', Math.abs(f - 97.5) < 0.2);

    await fm.setVolume(10);
    checkTrue('signal_strength in range', (await fm.signalStrength()) >= 0 && (await fm.signalStrength()) <= 127);
    checkTrue('is_stereo is boolean', typeof (await fm.isStereo()) === 'boolean');

    await fm.mute(true);
    await fm.mute(false);
    sleep(SETTLE_MS);
    checkTrue('mute/unmute: is_ready after', await fm.isReady());

    const seekFreq = await fm.seek(true);
    checkTrue('seek: result is number or null', seekFreq === null || typeof seekFreq === 'number');

    await fm.enableRds(true);
    checkTrue('rds_ready is boolean', typeof (await fm.rdsReady()) === 'boolean');

    await fm.configure({ band: RDA5807MFull.BAND_WORLD, space: RDA5807MFull.SPACE_100K });
    sleep(SETTLE_MS);
    checkTrue('after configure: is_ready', await fm.isReady());

    await fm.standby(true);
    sleep(10);
    await fm.standby(false);
    checkTrue('after standby cycle: is_ready', await fm.isReady());

    await fm.softReset();
    checkTrue('after soft_reset: is_ready', await fm.isReady());

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
