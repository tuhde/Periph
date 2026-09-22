'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS3231Minimal } = require('periph/src/chips/rtc/ds3231');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x68);
    const rtc = new DS3231Minimal(connection);                                     // Create DS3231 driver, (connection)
    await rtc.init();                                                              // Confirm device presence, () → None

    await rtc.setDatetime(2026, 9, 22, 2, 14, 30, 0);                              // Set the calendar clock, (year, month, day, weekday, hour, minute, second) → None

    for (let i = 0; i < 10; i++) {
        const dt = await rtc.getDatetime();                                         // Read the calendar clock, () → {year, month, ..., second}
        const tempC = await rtc.readTemperature();                                   // Read temperature, () → float C
        console.log(`${dt.year}-${String(dt.month).padStart(2, '0')}-${String(dt.day).padStart(2, '0')} ` +
            `${String(dt.hour).padStart(2, '0')}:${String(dt.minute).padStart(2, '0')}:${String(dt.second).padStart(2, '0')}  ${tempC.toFixed(2)} C`);
        await new Promise((r) => setTimeout(r, 1000));
    }

    await connection.close();
})();
