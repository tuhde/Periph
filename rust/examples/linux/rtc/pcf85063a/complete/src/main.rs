use linux_embedded_hal::I2cdev;
use periph::chips::rtc::{
    MinuteInterrupt, OscillatorLoad, Pcf85063aAlarm, Pcf85063aDateTime, Pcf85063aFull,
    Pcf85063aOffsetMode, Pcf85063aSourceClock,
    PCF85063A_SOURCE_ALARM, PCF85063A_SOURCE_CORRECTION, PCF85063A_SOURCE_HALF_MINUTE,
    PCF85063A_SOURCE_MINUTE, PCF85063A_SOURCE_TIMER,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Pcf85063aFull::new(dev, 0x51).expect("init PCF85063A"); // Create PCF85063A Full driver, (i2c, addr=0x51)
                                                        // bus check, forces 24-hour mode with STOP = 0

    rtc.set_datetime(Pcf85063aDateTime {
        year: 2026, month: 9, day: 23, weekday: 3, hour: 14, minute: 30, second: 0,
    }).expect("set_datetime");                          // Set the calendar clock, (datetime, year 2000–2099, weekday 0=Sun) → ()
                                                        // STOP-bit precision start; clears OS
    let dt = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
                                                        // decodes the seven BCD clock/calendar registers
    let stopped = rtc.oscillator_stopped().expect("oscillator_stopped"); // Query oscillator-stop flag, () → bool
                                                        // true means the time may be invalid until set_datetime
    rtc.clear_oscillator_stop().expect("clear_oscillator_stop"); // Clear oscillator-stop flag, () → ()
                                                        // still true afterwards means the crystal is not running
    rtc.stop_clock().expect("stop_clock");              // Freeze the clock, () → ()
                                                        // STOP = 1, divider chain held in reset
    rtc.start_clock().expect("start_clock");            // Release the clock, () → ()

    rtc.set_alarm(Pcf85063aAlarm { second: Some(0), minute: Some(0), hour: Some(9), day: None, weekday: None })
        .expect("set_alarm");                           // Configure alarm, (Alarm { second, minute, hour, day, weekday }) → ()
                                                        // fires daily at 09:00:00; None fields are ignored in the match
    let alarm = rtc.get_alarm().expect("get_alarm");    // Read alarm, () → Alarm
                                                        // disabled fields decode as None

    rtc.configure_timer(10, Pcf85063aSourceClock::Hz1, false)
        .expect("configure_timer");                     // Start countdown timer, (value 1–255, source_clock, pulsed) → ()
                                                        // counts down 10 s, then sets TF
    let remaining = rtc.read_timer().expect("read_timer"); // Read timer counter, () → u8
                                                        // live value, not the loaded one
    rtc.disable_timer().expect("disable_timer");        // Stop timer, () → ()
                                                        // TE = 0, TIE = 0, 1/60 Hz clock for power saving

    rtc.set_minute_interrupt(MinuteInterrupt::Minute)
        .expect("set_minute_interrupt");                // Select minute interrupt, (mode Off|Minute|HalfMinute) → ()
                                                        // sets MI; reported as SOURCE_TIMER

    rtc.set_clock_output(1).expect("set_clock_output"); // Drive CLKOUT, (frequency_hz) → ()
                                                        // 1 Hz square wave on the CLKOUT pin
    rtc.disable_clock_output().expect("disable_clock_output"); // Disable CLKOUT, () → ()
                                                        // COF = 111, pin low

    rtc.set_offset(-3, Pcf85063aOffsetMode::Normal).expect("set_offset"); // Write offset calibration, (offset −64–63, mode) → ()
                                                        // −3 LSB × 4.34 ppm = −13.02 ppm correction
    let (offset, offset_mode) = rtc.get_offset().expect("get_offset"); // Read offset calibration, () → (i8, OffsetMode)

    rtc.set_oscillator_load(OscillatorLoad::Pf12_5).expect("set_oscillator_load"); // Select crystal load, (load Pf7|Pf12_5) → ()
                                                        // match the crystal's specified load capacitance
    rtc.write_ram(0xA5).expect("write_ram");            // Write RAM byte, (value 0–255) → ()
    let ram = rtc.read_ram().expect("read_ram");        // Read RAM byte, () → u8

    rtc.enable_interrupt(PCF85063A_SOURCE_ALARM | PCF85063A_SOURCE_TIMER)
        .expect("enable_interrupt");                    // Enable sources, (source) → ()
                                                        // sets AIE and TIE
    let status = rtc.poll_interrupt().expect("poll_interrupt"); // Poll & clear flags, () → u8
                                                        // clears AF/TF, returns the pre-clear mask
    rtc.disable_interrupt(PCF85063A_SOURCE_ALARM | PCF85063A_SOURCE_TIMER | PCF85063A_SOURCE_MINUTE
        | PCF85063A_SOURCE_HALF_MINUTE | PCF85063A_SOURCE_CORRECTION)
        .expect("disable_interrupt");                   // Disable sources, (source) → ()

    rtc.software_reset().expect("software_reset");      // Software reset, () → ()
                                                        // resets ALL registers including the time

    println!("{:?} os_stopped={}", dt, stopped);
    println!("{:?} timer={} ram=0x{:02X}", alarm, remaining, ram);
    println!("offset={} {:?} status=0x{:02X}", offset, offset_mode, status);
}
