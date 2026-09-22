'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS3231Full } = require('periph/src/chips/rtc/ds3231');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x68);
    const rtc = new DS3231Full(connection);                                        // Create DS3231 Full driver, (connection)
    await rtc.init();                                                              // Confirm device presence, () → None

    if (await rtc.oscillatorStopped()) {                                           // Query oscillator-stop flag, () → bool
                                                                                       // true means timekeeping data may be invalid since the last check
        await rtc.setDatetime(2026, 9, 22, 2, 14, 30, 0);                          // Set the calendar clock, (year, month, day, weekday, hour, minute, second) → None
                                                                                       // also clears OSF, since the time is now known-good
    }

    const dt = await rtc.getDatetime();                                           // Read the calendar clock, () → {year, month, ..., second}
                                                                                       // inherited from DS3231Minimal
    console.log(dt);

    const tempC = await rtc.readTemperature();                                    // Read cached temperature, () → float C
                                                                                       // may be up to 64 s stale (autonomous conversion cycle)
    console.log('cached temp:', tempC);

    const freshTempC = await rtc.forceTemperatureConversion();                     // Force a fresh conversion, () → float C
                                                                                       // sets CONV and polls BSY, max 200 ms
    console.log('fresh temp:', freshTempC);

    await rtc.setAlarm1(0, 0, 0, 1, false, DS3231Full.ALARM1_EVERY_SECOND);        // Configure alarm 1, (second, minute, hour, day_or_date, is_day_of_week, match_mode) → None
                                                                                       // once-per-second match; day_or_date/is_day_of_week ignored for this mode
    const alarm1 = await rtc.getAlarm1();                                          // Read alarm 1 config, () → {second, minute, ..., matchMode}
    console.log('alarm1:', alarm1);

    await rtc.setAlarm2(30, 0, 1, false, DS3231Full.ALARM2_MATCH_MINUTES);         // Configure alarm 2, (minute, hour, day_or_date, is_day_of_week, match_mode) → None
                                                                                       // fires when minutes register reads 30
    const alarm2 = await rtc.getAlarm2();                                          // Read alarm 2 config, () → {minute, hour, ..., matchMode}
    console.log('alarm2:', alarm2);

    await rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2); // Enable alarm interrupts, (source) → None
                                                                                       // sets A1IE/A2IE and INTCN=1 (INT/SQW now carries alarm interrupts)

    await rtc.onInterrupt((status) => {                                            // Subscribe to alarm interrupts, (callback) → None
        if (status & DS3231Full.SOURCE_ALARM1) console.log('alarm 1 fired');
        if (status & DS3231Full.SOURCE_ALARM2) console.log('alarm 2 fired');
    });
    await new Promise((r) => setTimeout(r, 3000));
    await rtc.offInterrupt();                                                      // Unsubscribe, () → None

    const status = await rtc.pollInterrupt();                                      // Poll & clear alarm flags, () → int
    console.log('leftover status:', status);

    await rtc.disableInterrupt(DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2); // Disable alarm interrupts, (source) → None

    await rtc.enableSquareWave(1024, false);                                       // Enable square wave, (rate_hz=8192, battery_backed=false) → None
                                                                                       // 1.024 kHz on INT/SQW; mutually exclusive with alarm interrupts
    await rtc.disableSquareWave();                                                 // Disable square wave, () → None
                                                                                       // returns INT/SQW to interrupt mode

    console.log('32kHz enabled:', await rtc.is32khzEnabled());                     // Query 32kHz output, () → bool
    await rtc.disable32khzOutput();                                                // Disable 32kHz output, () → None
    await rtc.enable32khzOutput();                                                 // Enable 32kHz output, () → None

    await rtc.disableBatteryOscillator();                                         // Disable oscillator on VBAT, () → None
                                                                                       // saves battery current; oscillator resumes once VCC returns
    await rtc.enableBatteryOscillator();                                          // Enable oscillator on VBAT, () → None
                                                                                       // power-on default

    await rtc.clearOscillatorStopped();                                          // Clear oscillator-stop flag, () → None

    const aging = await rtc.getAgingOffset();                                    // Read aging offset, () → int
                                                                                       // raw signed 8-bit trim code, no fixed physical unit
    console.log('aging offset:', aging);
    await rtc.setAgingOffset(0);                                                 // Set aging offset, (offset) → None

    await connection.close();
})();
