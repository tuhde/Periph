'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { PCA9685Minimal, PCA9685Full } = require('../../packages/periph/src/chips/led/pca9685');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0, failed = 0;

function check(label, ok, extra) {
    if (ok) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label + (extra ? ': ' + extra : '')); failed++; }
}

async function attempt(label, fn) {
    try { await fn(); check(label, true); }
    catch (e) { check(label, false, e.message); }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const pwm = new PCA9685Minimal(connection);
    await pwm.ready;
    check('constants', PCA9685Minimal.CHANNELS === 16);
    await attempt('set_duty', async () => { await pwm.setDuty(0, 0.5); await pwm.setDuty(15, 0.0); await pwm.setDuty(1, 1.0); });

    const full = new PCA9685Full(connection);
    await full.ready;
    await full.setPwm(2, 409, 1228);
    const p = await full.getPwm(2);
    check('get_pwm', p.on === 409 && p.off === 1228);
    await attempt('full_on_off', async () => { await full.setFullOn(3); await full.setFullOff(3); });
    await attempt('all', async () => { await full.setAllDuty(0.1); await full.setAllPwm(0, 2048); await full.setAllDuty(0.0); });
    await full.setFrequency(50);
    check('frequency', Math.abs(await full.getFrequency() - 50.0) < 1.0);
    await attempt('pulse_width', () => full.setPulseWidth(15, 0.0015));
    await attempt('sleep_wake', async () => { await full.sleep(); await full.wake(); await full.restart(); });
    await attempt('output_mode', async () => { await full.setOutputMode(false, false, 0); await full.setUpdateOnAck(false); });
    await attempt('allcall', () => full.setAllcall(false));
    await attempt('subaddress', () => full.setSubaddress(1, 0x71, false));
    const m = await full.readMode();
    check('read_mode', (m.mode1 & 0x10) === 0 && (m.mode2 & 0x04) === 0x04);
    await attempt('reset', () => full.reset());

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
