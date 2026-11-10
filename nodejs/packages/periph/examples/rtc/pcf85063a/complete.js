'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCF85063AFull } = require('periph/src/chips/rtc/pcf85063a');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x51);
    const rtc = new PCF85063AFull(connection);                                     // Create PCF85063A Full driver, (connection)
    await rtc.init();                                                              // Confirm presence + force 24-hour mode, () → None
                                                                                   // bus check, STOP = 0, 12_24 = 0

    await rtc.setDatetime(2026, 9, 23, 3, 14, 30, 0);                              // Set the calendar clock, (year 2000–2099, month, day, weekday 0=Sun, hour, minute, second) → None
                                                                                   // STOP-bit precision start; clears OS
    const dt = await rtc.getDatetime();                                            // Read the calendar clock, () → {year, month, day, weekday, hour, minute, second}
                                                                                   // decodes the seven BCD clock/calendar registers
    const stopped = await rtc.oscillatorStopped();                                 // Query oscillator-stop flag, () → bool
                                                                                   // true means the time may be invalid until setDatetime
    await rtc.clearOscillatorStop();                                               // Clear oscillator-stop flag, () → None
                                                                                   // still true afterwards means the crystal is not running
    await rtc.stopClock();                                                         // Freeze the clock, () → None
                                                                                   // STOP = 1, divider chain held in reset
    await rtc.startClock();                                                        // Release the clock, () → None

    await rtc.setAlarm({ second: 0, minute: 0, hour: 9 });                         // Configure alarm, ({second, minute, hour, day, weekday}) → None
                                                                                   // fires daily at 09:00:00; omitted fields are ignored in the match
    const alarm = await rtc.getAlarm();                                            // Read alarm, () → {second, minute, hour, day, weekday}
                                                                                   // disabled fields decode as null

    await rtc.configureTimer(10, '1hz');                                           // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → None
                                                                                   // counts down 10 s, then sets TF
    const remaining = await rtc.readTimer();                                       // Read timer counter, () → int
                                                                                   // live value, not the loaded one
    await rtc.disableTimer();                                                      // Stop timer, () → None
                                                                                   // TE = 0, TIE = 0, 1/60 Hz clock for power saving

    await rtc.setMinuteInterrupt('minute');                                        // Select minute interrupt, (mode "off"|"minute"|"half_minute") → None
                                                                                   // sets MI; reported as SOURCE_TIMER

    await rtc.setClockOutput(1);                                                   // Drive CLKOUT, (frequencyHz) → None
                                                                                   // 1 Hz square wave on the CLKOUT pin
    await rtc.disableClockOutput();                                                // Disable CLKOUT, () → None
                                                                                   // COF = 111, pin low

    await rtc.setOffset(-3, 'normal');                                             // Write offset calibration, (offset −64–63, mode='normal') → None
                                                                                   // −3 LSB × 4.34 ppm = −13.02 ppm correction
    const offset = await rtc.getOffset();                                          // Read offset calibration, () → {offset, mode}

    await rtc.setOscillatorLoad(12.5);                                             // Select crystal load, (picofarads 7|12.5) → None
                                                                                   // match the crystal's specified load capacitance
    await rtc.writeRam(0xA5);                                                      // Write RAM byte, (value 0–255) → None
    const ram = await rtc.readRam();                                               // Read RAM byte, () → int

    await rtc.onInterrupt((status) => console.log('interrupt status=0x' + status.toString(16)));  // Subscribe to interrupts, (callback) → None
                                                                                   // falls back to 5 ms polling when no INT pin is wired
    await rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER);  // Enable sources, (source) → None
                                                                                   // sets AIE and TIE
    const status = await rtc.pollInterrupt();                                      // Poll & clear flags, () → int
                                                                                   // clears AF/TF, returns the pre-clear mask
    await rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_TIMER | PCF85063AFull.SOURCE_MINUTE | PCF85063AFull.SOURCE_HALF_MINUTE | PCF85063AFull.SOURCE_CORRECTION);  // Disable sources, (source) → None
    await rtc.offInterrupt();                                                      // Unsubscribe, () → None

    await rtc.softwareReset();                                                     // Software reset, () → None
                                                                                   // resets ALL registers including the time

    console.log(dt, { stopped }, alarm, { remaining, ram }, offset, { status });
    await connection.close();
})();
