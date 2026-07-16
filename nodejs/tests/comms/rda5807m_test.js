'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
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

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const fm = new RDA5807MFull(transport, 100.0, 8);

checkTrue('is_ready', fm.isReady());

let f = fm.frequency();
checkTrue('frequency near 100.0 MHz', Math.abs(f - 100.0) < 0.2);

fm.setFrequency(97.5);
f = fm.frequency();
checkTrue('set_frequency: frequency near 97.5 MHz', Math.abs(f - 97.5) < 0.2);

fm.setVolume(10);
checkTrue('signal_strength in range', fm.signalStrength() >= 0 && fm.signalStrength() <= 127);
checkTrue('is_stereo is boolean', typeof fm.isStereo() === 'boolean');

fm.mute(true);
fm.mute(false);
checkTrue('mute/unmute: is_ready after', fm.isReady());

const seekFreq = fm.seek(true);
checkTrue('seek: result is number or null', seekFreq === null || typeof seekFreq === 'number');

fm.enableRds(true);
checkTrue('rds_ready is boolean', typeof fm.rdsReady() === 'boolean');

fm.configure({ band: RDA5807MFull.BAND_WORLD, space: RDA5807MFull.SPACE_100K });
checkTrue('after configure: is_ready', fm.isReady());

fm.standby(true);
sleep(10);
fm.standby(false);
sleep(10);
checkTrue('after standby cycle: is_ready', fm.isReady());

fm.softReset();
checkTrue('after soft_reset: is_ready', fm.isReady());

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
