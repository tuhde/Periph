'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCF8523Full } = require('periph/src/chips/rtc/pcf8523');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x68);
    const rtc = new PCF8523Full(connection);                                       // Create PCF8523 Full driver, (connection)
    await rtc.init();                                                              // Confirm presence + enable battery backup, () → None
                                                                                   // writes PM=000: standard switch-over, low detection on

    await rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0);                              // Set the calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → None
                                                                                   // STOP-bit precision start; forces 24-hour mode and clears OS
    const dt = await rtc.getDatetime();                                            // Read the calendar clock, () → {year, month, day, weekday, hour, minute, second}
                                                                                   // decodes the seven BCD clock/calendar registers
    const stopped = await rtc.oscillatorStopped();                                 // Query oscillator-stop flag, () → bool
                                                                                   // true means the time may be invalid until setDatetime

    await rtc.setAlarm({ minute: 0, hour: 9 });                                    // Configure alarm, ({minute, hour, day, weekday}) → None
                                                                                   // fires daily at 09:00; omitted fields are ignored in the match
    const alarm = await rtc.getAlarm();                                            // Read alarm, () → {minute, hour, day, weekday}
                                                                                   // disabled fields decode as null

    await rtc.configureTimerA('countdown', 10, '1hz');                             // Start Timer A, (mode, value 0–255, sourceClock, pulsed=false) → None
                                                                                   // counts down 10 s, then sets CTAF
    const remainingA = await rtc.readTimerA();                                     // Read Timer A counter, () → int
                                                                                   // live value, not the loaded one
    await rtc.disableTimerA();                                                     // Stop Timer A, () → None

    await rtc.configureTimerB(30, '1hz', 62.5, true);                              // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → None
                                                                                   // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
    const remainingB = await rtc.readTimerB();                                     // Read Timer B counter, () → int
    await rtc.disableTimerB();                                                     // Stop Timer B, () → None

    await rtc.setClockOutput(1);                                                   // Drive CLKOUT, (frequencyHz) → None
                                                                                   // 1 Hz square wave on the shared INT1/CLKOUT pin
    await rtc.disableClockOutput();                                                // Disable CLKOUT, () → None
                                                                                   // frees INT1 for interrupts

    await rtc.setOffset(-3, 'every_two_hours');                                    // Write offset calibration, (offset −64–63, mode='every_two_hours') → None
                                                                                   // −3 LSB × 4.34 ppm = −13.02 ppm correction
    const offset = await rtc.getOffset();                                          // Read offset calibration, () → {offset, mode}

    await rtc.configureBatteryBackup('standard', true);                            // Select battery switch-over, (mode, lowDetection=true) → None
                                                                                   // switches to VBAT when VDD < VBAT and VDD < 2.5 V
    const switched = await rtc.isBatterySwitchedOver();                            // Query switch-over flag, () → bool
    await rtc.clearBatterySwitchover();                                            // Clear switch-over flag, () → None
    const low = await rtc.isBatteryLow();                                          // Query battery-low flag, () → bool
                                                                                   // read-only; clears itself once the cell is replaced

    await rtc.onInterrupt((status) => console.log('interrupt status=0x' + status.toString(16)));  // Subscribe to interrupts, (callback) → None
                                                                                   // falls back to 5 ms polling when no INT pin is wired
    await rtc.enableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_B | PCF8523Full.SOURCE_BATTERY_LOW);  // Enable sources, (source) → None
                                                                                   // sets AIE, CTBIE and BLIE
    const status = await rtc.pollInterrupt();                                      // Poll & clear flags, () → int
                                                                                   // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
    await rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_TIMER_A | PCF8523Full.SOURCE_TIMER_B | PCF8523Full.SOURCE_BATTERY_LOW);  // Disable sources, (source) → None
    await rtc.offInterrupt();                                                      // Unsubscribe, () → None

    await rtc.softwareReset();                                                     // Software reset, () → None
                                                                                   // control registers back to POR (PM=111); time is kept

    console.log(dt, { stopped }, alarm, { remainingA, remainingB }, offset, { switched, low, status });
    await connection.close();
})();
