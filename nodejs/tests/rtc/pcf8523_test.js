'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { PCF8523Full } = require('../../packages/periph/src/chips/rtc/pcf8523');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const rtc = new PCF8523Full(connection);
    await rtc.init();
    checkTrue('init', true);

    await rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0);
    const dt = await rtc.getDatetime();
    checkTrue('datetime date', dt.year === 2026 && dt.month === 9 && dt.day === 23 && dt.weekday === 3);
    checkTrue('datetime time', dt.hour === 14 && (dt.minute === 30 || dt.minute === 31));
    checkTrue('oscillator not stopped after setDatetime', !(await rtc.oscillatorStopped()));

    await rtc.setAlarm({ minute: 15, hour: 6 });
    const alarm = await rtc.getAlarm();
    checkTrue('alarm roundtrip', alarm.minute === 15 && alarm.hour === 6 && alarm.day === null && alarm.weekday === null);
    await rtc.setAlarm();

    await rtc.setOffset(-3, 'every_minute');
    const off = await rtc.getOffset();
    checkTrue('offset roundtrip', off.offset === -3 && off.mode === 'every_minute');
    await rtc.setOffset(0);

    // Timer B at 64 Hz from 64 counts should expire in ~1 s.
    await rtc.pollInterrupt();
    await rtc.configureTimerB(64, '64hz');
    let fired = false;
    const deadline = Date.now() + 3000;
    while (Date.now() < deadline) {
        if ((await rtc.pollInterrupt()) & PCF8523Full.SOURCE_TIMER_B) { fired = true; break; }
        await new Promise((r) => setTimeout(r, 50));
    }
    checkTrue('timer b fires', fired);
    await rtc.disableTimerB();

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
