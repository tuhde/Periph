'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCF8523Minimal } = require('periph/src/chips/rtc/pcf8523');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x68);
    const rtc = new PCF8523Minimal(connection);                                    // Create PCF8523 driver, (connection)
    await rtc.init();                                                              // Confirm presence + enable battery backup, () → None

    const pad = (n) => String(n).padStart(2, '0');
    for (let i = 0; i < 10; i++) {
        const dt = await rtc.getDatetime();                                        // Read the calendar clock, () → {year, month, day, weekday, hour, minute, second}
        console.log(`${dt.year}-${pad(dt.month)}-${pad(dt.day)} ${pad(dt.hour)}:${pad(dt.minute)}:${pad(dt.second)}`);
        await new Promise((r) => setTimeout(r, 1000));
    }

    await connection.close();
})();
