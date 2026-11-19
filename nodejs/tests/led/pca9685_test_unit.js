'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCA9685Minimal, PCA9685Full } = require('../../packages/periph/src/chips/led/pca9685');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const b = (...bytes) => Buffer.from(bytes);

async function throwsRange(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

async function main() {
    const c = new I2CConnectionMock();
    // register-pointer writes of readReg() are not data writes
    const data = () => c.writes.filter((w) => w.length > 1);
    const clear = () => { c.writes.length = 0; };
    const same = (list) => { const d = data(); return d.length === list.length && list.every((x, i) => d[i].equals(x)); };
    const last = () => data()[data().length - 1];

    const d = new PCA9685Full(c);
    await d.ready;
    checkTrue('init_sequence', same([b(0x00, 0x30), b(0xFE, 5), b(0x00, 0x20)]));

    clear();
    await d.setDuty(0, 0.5);
    checkTrue('duty_half', same([b(0x06, 0, 0, 0x00, 0x08)]));
    await d.setDuty(15, 0.0);
    checkTrue('duty_zero_full_off', last().equals(b(0x42, 0, 0, 0, 0x10)));
    await d.setDuty(1, 1.0);
    checkTrue('duty_one_full_on', last().equals(b(0x0A, 0, 0x10, 0, 0)));
    checkTrue('duty_range', await throwsRange(() => d.setDuty(16, 0.5)) && await throwsRange(() => d.setDuty(0, 1.1))
        && await throwsRange(() => d.setDuty(-1, 0.5)));

    await d.setPwm(2, 409, 1228);
    checkTrue('set_pwm', last().equals(b(0x0E, 0x99, 0x01, 0xCC, 0x04)));
    const p = await d.getPwm(2);
    checkTrue('get_pwm', p.on === 409 && p.off === 1228);
    checkTrue('pwm_range', await throwsRange(() => d.setPwm(0, 4096, 1)) && await throwsRange(() => d.setPwm(0, 5, 5)));

    await d.setFullOn(3);
    checkTrue('full_on', last().equals(b(0x12, 0, 0x10, 0, 0)));
    await d.setFullOff(3);
    checkTrue('full_off', last().equals(b(0x12, 0, 0, 0, 0x10)));
    await d.setAllDuty(0.25);
    checkTrue('all_duty', last().equals(b(0xFA, 0, 0, 0x00, 0x04)));
    await d.setAllPwm(0, 100);
    checkTrue('all_pwm', last().equals(b(0xFA, 0, 0, 100, 0)));

    c.setRegister(0x00, [0x20]);
    clear();
    await d.setFrequency(50);
    checkTrue('set_frequency', same([b(0x00, 0x30), b(0xFE, 121), b(0x00, 0x20)]));
    checkTrue('get_frequency', Math.abs(await d.getFrequency() - 25e6 / (4096 * 122)) < 1e-6);
    await d.setFrequency(1e6);
    checkTrue('freq_clamp_min', data()[data().length - 2].equals(b(0xFE, 3)));
    await d.setFrequency(1);
    checkTrue('freq_clamp_max', data()[data().length - 2].equals(b(0xFE, 255)));
    c.setRegister(0x00, [0xA0]);
    clear();
    await d.setFrequency(200);
    checkTrue('set_frequency_restart', last().equals(b(0x00, 0xA0)) && data()[1].equals(b(0xFE, 30)));
    checkTrue('freq_range', await throwsRange(() => d.setFrequency(0)));

    c.setRegister(0xFE, [121]);
    await d.setPulseWidth(15, 0.0015);
    checkTrue('pulse_width', last().equals(b(0x42, 0, 0, 307 & 0xFF, 307 >> 8)));

    c.setRegister(0x00, [0x20]);
    clear();
    await d.sleep();
    checkTrue('sleep', last().equals(b(0x00, 0x30)));
    c.setRegister(0x00, [0xB0]);
    clear();
    await d.wake();
    checkTrue('wake_restart', same([b(0x00, 0x20), b(0x00, 0xA0)]));
    clear();
    await d.restart();
    checkTrue('restart', last().equals(b(0x00, 0xA0)));

    c.setRegister(0x01, [0x04]);
    await d.setOutputMode(true, true, 2);
    checkTrue('output_mode', last().equals(b(0x01, 0x12)));
    await d.setOutputMode();
    checkTrue('output_mode_default', last().equals(b(0x01, 0x04)));
    checkTrue('output_mode_range', await throwsRange(() => d.setOutputMode(false, false, 3)));
    await d.setUpdateOnAck(true);
    checkTrue('och_on', last().equals(b(0x01, 0x0C)));
    await d.setUpdateOnAck(false);
    checkTrue('och_off', last().equals(b(0x01, 0x04)));

    c.setRegister(0x00, [0x20]);
    clear();
    await d.useExternalClock();
    checkTrue('extclk', same([b(0x00, 0x30), b(0x00, 0x70), b(0x00, 0x60)]));

    c.setRegister(0x00, [0x20]);
    clear();
    await d.setAllcall(true, 0x70);
    checkTrue('allcall', same([b(0x05, 0xE0), b(0x00, 0x21)]));
    await d.setSubaddress(2, 0x71, true);
    checkTrue('subaddr', data()[data().length - 2].equals(b(0x03, 0xE2)) && last().equals(b(0x00, 0x25)));
    checkTrue('subaddr_range', await throwsRange(() => d.setSubaddress(4, 0x71)) && await throwsRange(() => d.setAllcall(true, 0x80)));

    clear();
    await d.reset();
    checkTrue('reset', data()[0].equals(b(0x00, 0x10)) && last().equals(b(0x00, 0x11)) && data().some((w) => w.equals(b(0xFE, 0x1E))));

    const g = new I2CConnectionMock();
    await PCA9685Full.softwareReset(g);
    checkTrue('software_reset', g.writes.length === 1 && g.writes[0].equals(b(0x06)));

    c.setRegister(0x00, [0x11, 0x04]);
    const m = await d.readMode();
    checkTrue('read_mode', m.mode1 === 0x11 && m.mode2 === 0x04);
    checkTrue('constants', PCA9685Minimal.CHANNELS === 16);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
