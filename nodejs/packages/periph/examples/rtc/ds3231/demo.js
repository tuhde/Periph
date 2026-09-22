'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS3231Full } = require('periph/src/chips/rtc/ds3231');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x68);
    const rtc = new DS3231Full(connection);                                        // Create DS3231 Full driver, (connection)
    await rtc.init();                                                              // Confirm device presence, () → None

    // --- Backup-clock module for a data logger ---
    // A fresh chip (or one whose coin cell just died and was replaced) reports
    // OSF=1: its timekeeping registers cannot be trusted. Reseed from a known
    // reference timestamp rather than logging garbage — setDatetime() also
    // clears OSF, so this only runs once per real power/battery loss.
    if (await rtc.oscillatorStopped()) {
        const now = new Date();
        const weekday = ((now.getUTCDay() + 6) % 7) + 1; // JS Sunday=0 -> ISO 8601 Monday=1..Sunday=7
        await rtc.setDatetime(now.getUTCFullYear(), now.getUTCMonth() + 1, now.getUTCDate(),
            weekday, now.getUTCHours(), now.getUTCMinutes(), now.getUTCSeconds());
        console.log('oscillator was stopped - reseeded clock from host time');
    }

    // --- Arm both alarms as periodic wake sources ---
    // Alarm 1 matches once per minute (at :00 seconds); Alarm 2 matches once
    // per hour (at :00 minutes). Neither register is ever rewritten, so once
    // armed they repeat indefinitely without host intervention.
    await rtc.setAlarm1(0, 0, 0, 1, false, DS3231Full.ALARM1_MATCH_SECONDS); // seconds==0 -> once per minute
    await rtc.setAlarm2(0, 0, 1, false, DS3231Full.ALARM2_EVERY_MINUTE);
    await rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2);

    let logEntries = 0;
    const MAX_ENTRIES = 5;
    let done;
    const finished = new Promise((resolve) => { done = resolve; });

    // --- Log one line per alarm match, tagged with which alarm(s) fired ---
    await rtc.onInterrupt(async (status) => {
        const dt = await rtc.getDatetime();
        const tempC = await rtc.readTemperature();
        const tags = [];
        if (status & DS3231Full.SOURCE_ALARM1) tags.push('minute');
        if (status & DS3231Full.SOURCE_ALARM2) tags.push('hour');
        const stamp = `${dt.year}-${String(dt.month).padStart(2, '0')}-${String(dt.day).padStart(2, '0')} ` +
            `${String(dt.hour).padStart(2, '0')}:${String(dt.minute).padStart(2, '0')}:${String(dt.second).padStart(2, '0')}`;
        console.log(`[${tags.join('+')}] ${stamp}  ${tempC.toFixed(2)} C`);

        logEntries++;
        if (logEntries >= MAX_ENTRIES) done();
    });

    await finished;

    // --- Clean up: silence both alarms and unsubscribe ---
    await rtc.disableInterrupt(DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2);
    await rtc.offInterrupt();

    await connection.close();
})();
