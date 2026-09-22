use linux_embedded_hal::{I2cdev, Delay};
use periph::chips::rtc::{Ds3231Full, DateTime, Alarm1Match, Alarm2Match, SOURCE_ALARM1, SOURCE_ALARM2};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Ds3231Full::new(dev, 0x68).expect("init DS3231"); // Create DS3231 Full driver, (i2c, addr=0x68)
    let mut delay = Delay;

    rtc.set_datetime(DateTime {
        year: 2026, month: 9, day: 22, weekday: 2, hour: 14, minute: 30, second: 0,
    }).expect("set_datetime"); // Set the calendar clock, (datetime) → ()
                                    // writes all seven clock/calendar registers and clears OSF

    let dt = rtc.get_datetime().expect("get_datetime");         // Read the calendar clock, () → DateTime
                                                                    // decodes BCD registers, always 24-hour, ISO 8601 weekday
    println!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);

    rtc.set_alarm1(0, 0, 0, 0, Alarm1Match::EverySecond).expect("set_alarm1"); // Set Alarm 1, (second, minute, hour, day_or_date, mode) → ()
                                                                    // once-per-second match, day_or_date unused for this mode
    let (s, m, h, dd, mode1) = rtc.get_alarm1().expect("get_alarm1"); // Read Alarm 1, () → (u8, u8, u8, u8, Alarm1Match)
                                                                    // decodes ALARM1_SECONDS..ALARM1_DAY_DATE
    println!("alarm1: {}:{}:{} day/date={} mode={:?}", h, m, s, dd, mode1);

    rtc.set_alarm2(0, 9, 0, Alarm2Match::HoursMinutes).expect("set_alarm2"); // Set Alarm 2, (minute, hour, day_or_date, mode) → ()
                                                                    // matches 09:00 every day
    let (m2, h2, dd2, mode2) = rtc.get_alarm2().expect("get_alarm2"); // Read Alarm 2, () → (u8, u8, u8, Alarm2Match)
                                                                    // decodes ALARM2_MINUTES..ALARM2_DAY_DATE
    println!("alarm2: {}:{} day/date={} mode={:?}", h2, m2, dd2, mode2);

    rtc.enable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2).expect("enable_interrupt"); // Enable alarm interrupts, (source) → ()
                                                                    // sets A1IE+A2IE and INTCN=1 on INT/SQW
    let status = rtc.poll_interrupt().expect("poll_interrupt");    // Poll interrupt status, () → u8
                                                                    // reads CONTROL_STATUS, clears A1F/A2F, returns pre-clear byte
    println!("alarm1_fired={} alarm2_fired={}", status & SOURCE_ALARM1 != 0, status & SOURCE_ALARM2 != 0);
    rtc.disable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2).expect("disable_interrupt"); // Disable alarm interrupts, (source) → ()
                                                                    // clears A1IE/A2IE, leaves INTCN untouched

    rtc.enable_square_wave(1, false).expect("enable_square_wave"); // Enable square wave, (rate_hz=1, battery_backed=false) → ()
                                                                    // sets INTCN=0, RS2:RS1 for 1 Hz
    rtc.disable_square_wave().expect("disable_square_wave");       // Disable square wave, () → ()
                                                                    // returns INT/SQW to interrupt mode (INTCN=1)

    rtc.enable_32khz_output().expect("enable_32khz_output");       // Enable 32kHz output, () → ()
                                                                    // sets EN32kHz in CONTROL_STATUS
    let khz = rtc.is_32khz_enabled().expect("is_32khz_enabled");   // Query 32kHz output, () → bool
                                                                    // reads EN32kHz back
    println!("32khz_enabled={}", khz);
    rtc.disable_32khz_output().expect("disable_32khz_output");     // Disable 32kHz output, () → ()
                                                                    // clears EN32kHz

    let osf = rtc.oscillator_stopped().expect("oscillator_stopped"); // Query oscillator-stop flag, () → bool
                                                                    // OSF bit — true means timekeeping may be stale
    println!("oscillator_stopped={}", osf);
    rtc.clear_oscillator_stopped().expect("clear_oscillator_stopped"); // Clear oscillator-stop flag, () → ()
                                                                    // writes 0 to OSF, preserves EN32kHz

    rtc.enable_battery_oscillator().expect("enable_battery_oscillator"); // Enable battery oscillator, () → ()
                                                                    // clears EOSC — oscillator keeps running on VBAT
    rtc.disable_battery_oscillator().expect("disable_battery_oscillator"); // Disable battery oscillator, () → ()
                                                                    // sets EOSC — oscillator stops on VBAT, saves current
    rtc.enable_battery_oscillator().expect("enable_battery_oscillator");

    rtc.force_temperature_conversion(&mut delay).expect("force_temperature_conversion"); // Force temperature conversion, (delay) → ()
                                                                    // sets CONV, polls BSY until clear (max 200 ms)
    let temp = rtc.read_temperature().expect("read_temperature");  // Read temperature, () → f32 C
                                                                    // 10-bit two's-complement, 0.25 C resolution
    println!("temperature={:.2} C", temp);

    rtc.set_aging_offset(0).expect("set_aging_offset");            // Set aging offset, (offset) → ()
                                                                    // raw signed trim code, no fixed physical scale
    let aging = rtc.get_aging_offset().expect("get_aging_offset"); // Read aging offset, () → i8
                                                                    // AGING_OFFSET register, two's-complement
    println!("aging_offset={}", aging);
}
