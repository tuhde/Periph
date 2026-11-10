'use strict';

// Scheduling core of a battery-powered logger: reseeds the clock after a
// power loss, counts boots in the free RAM byte, then wakes on an hourly
// alarm to print a timestamp while a 30-second pulsed countdown timer
// toggles an LED as a "still running" heartbeat.

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCF85063AFull } = require('periph/src/chips/rtc/pcf85063a');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x51);
    const rtc = new PCF85063AFull(connection);                                     // Create PCF85063A Full driver, (connection)
    await rtc.init();                                                              // Confirm presence + force 24-hour mode, () → None

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose supply dropped out, reports the OS flag set:
    // its calendar cannot be trusted until reseeded. The crystal needs up to
    // 2 s to start, so wait until the flag stays clear.
    if (await rtc.oscillatorStopped()) {                                           // Query oscillator-stop flag, () → bool
        await rtc.setDatetime(2026, 1, 1, 4, 0, 0, 0);                             // Set the calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → None
        console.log('oscillator was stopped - reseeded from reference timestamp');
        while (await rtc.oscillatorStopped()) {                                    // Query oscillator-stop flag, () → bool
            await new Promise((r) => setTimeout(r, 100));
        }
    }

    // --- Count boots in the free RAM byte ---
    // The RAM byte survives as long as VDD is present, so it works as a tiny
    // non-volatile counter next to the clock.
    const boots = ((await rtc.readRam()) + 1) & 0xFF;                              // Read RAM byte, () → int
    await rtc.writeRam(boots);                                                     // Write RAM byte, (value 0–255) → None
    console.log(`boot #${boots}`, await rtc.getDatetime(), await rtc.getOffset()); // Read the calendar clock, () → {year, month, day, weekday, hour, minute, second}
                                                                                   // Read offset calibration, () → {offset, mode}

    // --- Tune the board for low power ---
    // Match the crystal's load capacitance and switch the 32.768 kHz CLKOUT
    // off — it is enabled at power-on and costs current on a battery device.
    await rtc.setOscillatorLoad(12.5);                                             // Select crystal load, (picofarads 7|12.5) → None
    await rtc.disableClockOutput();                                                // Disable CLKOUT, () → None

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only second and minute are enabled, so the alarm matches at hh:00:00
    // every hour. The pulsed timer reloads automatically; minute ticks share
    // the same TF flag and are reported as SOURCE_TIMER.
    await rtc.setAlarm({ second: 0, minute: 0 });                                  // Configure alarm, ({second, minute, hour, day, weekday}) → None
    await rtc.configureTimer(30, '1hz', true);                                     // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → None
    await rtc.setMinuteInterrupt('minute');                                        // Select minute interrupt, (mode "off"|"minute"|"half_minute") → None

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    let alarms = 0;
    let ledOn = false;
    await new Promise((resolve) => {
        const timeout = setTimeout(resolve, 5 * 60 * 1000);
        rtc.onInterrupt(async (status) => {                                        // Subscribe to interrupts, (callback) → None
            if (status & PCF85063AFull.SOURCE_ALARM) {
                const dt = await rtc.getDatetime();                                // Read the calendar clock, () → {year, month, day, weekday, hour, minute, second}
                console.log('[hourly]', dt);
                if (++alarms >= 5) { clearTimeout(timeout); resolve(); }
            }
            if (status & PCF85063AFull.SOURCE_TIMER) {
                ledOn = !ledOn;
                console.log(`[heartbeat] LED ${ledOn ? 'on' : 'off'}`);
            }
        });
        rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER);  // Enable sources, (source) → None
    });

    // --- Leave the chip quiet on exit ---
    await rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER);  // Disable sources, (source) → None
    await rtc.disableTimer();                                                      // Stop timer, () → None
    await rtc.setMinuteInterrupt('off');                                           // Select minute interrupt, (mode "off"|"minute"|"half_minute") → None
    await rtc.offInterrupt();                                                      // Unsubscribe, () → None
    await connection.close();
})();
