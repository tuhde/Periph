use linux_embedded_hal::I2cdev;
use periph::chips::rtc::{
    BatteryMode, OffsetMode, Pcf8523Alarm, Pcf8523DateTime, Pcf8523Full, SourceClock, TimerAMode,
    PCF8523_SOURCE_ALARM, PCF8523_SOURCE_BATTERY_LOW, PCF8523_SOURCE_TIMER_A, PCF8523_SOURCE_TIMER_B,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Pcf8523Full::new(dev, 0x68).expect("init PCF8523"); // Create PCF8523 Full driver, (i2c, addr=0x68)
                                                        // enables battery switch-over standard mode (PM=000)

    rtc.set_datetime(Pcf8523DateTime {
        year: 2026, month: 9, day: 23, weekday: 3, hour: 14, minute: 30, second: 0,
    }).expect("set_datetime");                          // Set the calendar clock, (datetime, weekday 0=Sun) → ()
                                                        // STOP-bit precision start; forces 24-hour mode and clears OS
    let dt = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
                                                        // decodes the seven BCD clock/calendar registers
    let stopped = rtc.oscillator_stopped().expect("oscillator_stopped"); // Query oscillator-stop flag, () → bool
                                                        // true means the time may be invalid until set_datetime

    rtc.set_alarm(Pcf8523Alarm { minute: Some(0), hour: Some(9), day: None, weekday: None })
        .expect("set_alarm");                           // Configure alarm, (Alarm { minute, hour, day, weekday }) → ()
                                                        // fires daily at 09:00; None fields are ignored in the match
    let alarm = rtc.get_alarm().expect("get_alarm");    // Read alarm, () → Alarm
                                                        // disabled fields decode as None

    rtc.configure_timer_a(TimerAMode::Countdown, 10, SourceClock::Hz1, false)
        .expect("configure_timer_a");                   // Start Timer A, (mode, value 0–255, source_clock, pulsed) → ()
                                                        // counts down 10 s, then sets CTAF
    let remaining_a = rtc.read_timer_a().expect("read_timer_a"); // Read Timer A counter, () → u8
                                                        // live value, not the loaded one
    rtc.disable_timer_a().expect("disable_timer_a");    // Stop Timer A, () → ()

    rtc.configure_timer_b(30, SourceClock::Hz1, 62.5, true)
        .expect("configure_timer_b");                   // Start Timer B, (value 0–255, source_clock, pulse_width_ms, pulsed) → ()
                                                        // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
    let remaining_b = rtc.read_timer_b().expect("read_timer_b"); // Read Timer B counter, () → u8
    rtc.disable_timer_b().expect("disable_timer_b");    // Stop Timer B, () → ()

    rtc.set_clock_output(1).expect("set_clock_output"); // Drive CLKOUT, (frequency_hz) → ()
                                                        // 1 Hz square wave on the shared INT1/CLKOUT pin
    rtc.disable_clock_output().expect("disable_clock_output"); // Disable CLKOUT, () → ()
                                                        // frees INT1 for interrupts

    rtc.set_offset(-3, OffsetMode::EveryTwoHours).expect("set_offset"); // Write offset calibration, (offset −64–63, mode) → ()
                                                        // −3 LSB × 4.34 ppm = −13.02 ppm correction
    let (offset, offset_mode) = rtc.get_offset().expect("get_offset"); // Read offset calibration, () → (i8, OffsetMode)

    rtc.configure_battery_backup(BatteryMode::Standard, true)
        .expect("configure_battery_backup");            // Select battery switch-over, (mode, low_detection) → ()
                                                        // switches to VBAT when VDD < VBAT and VDD < 2.5 V
    let switched = rtc.is_battery_switched_over().expect("is_battery_switched_over"); // Query switch-over flag, () → bool
    rtc.clear_battery_switchover().expect("clear_battery_switchover"); // Clear switch-over flag, () → ()
    let low = rtc.is_battery_low().expect("is_battery_low"); // Query battery-low flag, () → bool
                                                        // read-only; clears itself once the cell is replaced

    rtc.enable_interrupt(PCF8523_SOURCE_ALARM | PCF8523_SOURCE_TIMER_B | PCF8523_SOURCE_BATTERY_LOW)
        .expect("enable_interrupt");                    // Enable sources, (source) → ()
                                                        // sets AIE, CTBIE and BLIE
    let status = rtc.poll_interrupt().expect("poll_interrupt"); // Poll & clear flags, () → u8
                                                        // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
    rtc.disable_interrupt(PCF8523_SOURCE_ALARM | PCF8523_SOURCE_TIMER_A | PCF8523_SOURCE_TIMER_B | PCF8523_SOURCE_BATTERY_LOW)
        .expect("disable_interrupt");                   // Disable sources, (source) → ()

    rtc.software_reset().expect("software_reset");      // Software reset, () → ()
                                                        // control registers back to POR (PM=111); time is kept

    println!("{:?} os_stopped={}", dt, stopped);
    println!("{:?} timer_a={} timer_b={}", alarm, remaining_a, remaining_b);
    println!("offset={} {:?} switched={} low={} status=0x{:02X}", offset, offset_mode, switched, low, status);
}
