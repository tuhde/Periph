'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { DS3231Full } = require('../../packages/periph/src/chips/rtc/ds3231');

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
    const rtc = new DS3231Full(connection);
    await rtc.init();
    checkTrue('init', true);

    await rtc.setDatetime(2026, 9, 22, 2, 14, 30, 0);
    const dt = await rtc.getDatetime();
    checkTrue('datetime year', dt.year === 2026);
    checkTrue('datetime month', dt.month === 9);
    checkTrue('datetime day', dt.day === 22);
    checkTrue('datetime weekday', dt.weekday === 2);
    checkTrue('datetime hour in range', dt.hour >= 14 && dt.hour <= 14 + 1); // allow a rollover second
    checkTrue('datetime second in range', dt.second >= 0 && dt.second < 60);

    checkTrue('oscillator not stopped after set_datetime', !(await rtc.oscillatorStopped()));

    const tempC = await rtc.readTemperature();
    checkTrue('temperature plausible', tempC > -20 && tempC < 60);

    const freshTempC = await rtc.forceTemperatureConversion();
    checkTrue('forced conversion plausible', freshTempC > -20 && freshTempC < 60);

    await rtc.setAlarm1(0, 0, 0, 1, false, DS3231Full.ALARM1_EVERY_SECOND);
    const alarm1 = await rtc.getAlarm1();
    checkTrue('alarm1 round trip', alarm1.matchMode === DS3231Full.ALARM1_EVERY_SECOND);

    await rtc.setAlarm2(0, 0, 1, false, DS3231Full.ALARM2_EVERY_MINUTE);
    const alarm2 = await rtc.getAlarm2();
    checkTrue('alarm2 round trip', alarm2.matchMode === DS3231Full.ALARM2_EVERY_MINUTE);

    await rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1);
    // Alarm 1 in "every second" mode should latch within ~1.1 s.
    let fired = false;
    const deadline = Date.now() + 3000;
    while (Date.now() < deadline) {
        const status = await rtc.pollInterrupt();
        if (status & DS3231Full.SOURCE_ALARM1) { fired = true; break; }
        await new Promise((r) => setTimeout(r, 100));
    }
    checkTrue('alarm1 fires in every-second mode', fired);
    await rtc.disableInterrupt(DS3231Full.SOURCE_ALARM1);

    const agingBefore = await rtc.getAgingOffset();
    await rtc.setAgingOffset(agingBefore); // round-trip only; don't perturb the chip's trim
    checkTrue('aging offset readable', typeof agingBefore === 'number');

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
