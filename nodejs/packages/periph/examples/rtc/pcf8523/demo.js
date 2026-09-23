'use strict';

// Scheduling core of a battery-backed logger: reseeds the clock after a
// power loss, checks the coin cell, then wakes on an hourly alarm to print
// a timestamp while Timer B pulses a 30-second "still running" heartbeat
// on INT2 that toggles an LED.

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCF8523Full } = require('periph/src/chips/rtc/pcf8523');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x68);
    const rtc = new PCF8523Full(connection);                                       // Create PCF8523 Full driver, (connection)
    await rtc.init();                                                              // Confirm presence + enable battery backup, () → None

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose backup cell was disconnected too long,
    // reports the OS flag set: its calendar cannot be trusted until reseeded.
    if (await rtc.oscillatorStopped()) {                                           // Query oscillator-stop flag, () → bool
        await rtc.setDatetime(2026, 1, 1, 4, 0, 0, 0);                             // Set the calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → None
        console.log('oscillator was stopped - reseeded from reference timestamp');
    }

    // --- Keep the clock alive through power cuts ---
    // Standard switch-over is already the driver default; it is repeated here
    // so the logger's power policy is explicit. A low coin cell is reported
    // once so it can be replaced before the next outage.
    await rtc.configureBatteryBackup('standard');                                  // Select battery switch-over, (mode, lowDetection=true) → None
    if (await rtc.isBatteryLow()) {                                                // Query battery-low flag, () → bool
        console.log('warning: backup battery low - replace the coin cell');
    }

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only the minute field is enabled, so the alarm matches at hh:00 every
    // hour. Timer B reloads automatically and has its own INT2 pin, so the
    // heartbeat keeps running independently of the hourly alarm.
    await rtc.disableClockOutput();                                                // Disable CLKOUT, () → None
    await rtc.setAlarm({ minute: 0 });                                             // Configure alarm, ({minute, hour, day, weekday}) → None
    await rtc.configureTimerB(30, '1hz');                                          // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → None

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    let alarms = 0;
    let ledOn = false;
    await new Promise((resolve) => {
        rtc.onInterrupt(async (status) => {                                        // Subscribe to interrupts, (callback) → None
            if (status & PCF8523Full.SOURCE_ALARM) {
                const dt = await rtc.getDatetime();                                // Read the calendar clock, () → {year, month, day, weekday, hour, minute, second}
                console.log('[hourly]', dt);
                if (++alarms >= 3) resolve();
            }
            if (status & PCF8523Full.SOURCE_TIMER_B) {
                ledOn = !ledOn;
                console.log(`[heartbeat] LED ${ledOn ? 'on' : 'off'}`);
            }
        });
        rtc.enableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_B);  // Enable sources, (source) → None
    });

    // --- Leave the chip quiet on exit ---
    await rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_B);  // Disable sources, (source) → None
    await rtc.disableTimerB();                                                     // Stop Timer B, () → None
    await rtc.offInterrupt();                                                      // Unsubscribe, () → None
    await connection.close();
})();
