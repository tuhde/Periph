use embedded_hal::delay::DelayNs;
use linux_embedded_hal::{I2cdev, Delay};
use periph::chips::rtc::{Ds3231Full, DateTime, Alarm1Match, Alarm2Match, SOURCE_ALARM1, SOURCE_ALARM2};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Ds3231Full::new(dev, 0x68).expect("init DS3231"); // Create DS3231 Full driver, (i2c, addr=0x68)
    let mut delay = Delay;

    // --- Reseed the clock if the oscillator ever lost power ---
    // OSF is set on first power-up or any time the coin cell couldn't keep
    // the oscillator running. A fresh/recovered chip can't be trusted to
    // hold a meaningful time, so this data logger falls back to a fixed
    // reference timestamp rather than trusting garbage registers.
    if rtc.oscillator_stopped().expect("oscillator_stopped") {
        rtc.set_datetime(DateTime {           // Set the calendar clock, (datetime) → ()
            year: 2026, month: 1, day: 1, weekday: 4, hour: 0, minute: 0, second: 0,
        }).expect("set_datetime");            // also clears OSF, since the time is now known-good
    }

    // --- Arm a once-per-minute and a once-per-hour alarm ---
    // Alarm 1 fires every minute (00 seconds) to log a frequent sample;
    // Alarm 2 fires every hour (00 minutes) to mark an hourly boundary in
    // the log. Both repeat automatically — their registers are never
    // rewritten — so this is a fire-and-forget periodic logging setup.
    rtc.set_alarm1(0, 0, 0, 0, Alarm1Match::Seconds).expect("set_alarm1"); // seconds=0 -> fires once per minute
    rtc.set_alarm2(0, 0, 0, Alarm2Match::HoursMinutes).expect("set_alarm2");
    rtc.enable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2).expect("enable_interrupt");

    // --- Poll for alarm matches and log each one ---
    // Rust drivers expose only poll_interrupt() (no callback subscription —
    // polling is always caller-managed here), so the demo runs its own
    // loop, checking CONTROL_STATUS every second. Each fired alarm is
    // logged with the current date/time and the on-chip temperature — a
    // one-line "log entry" — for a fixed number of minute-alarms.
    let mut minute_alarms = 0;
    while minute_alarms < 5 {
        let status = rtc.poll_interrupt().expect("poll_interrupt");
        if status & SOURCE_ALARM1 != 0 {
            let dt = rtc.get_datetime().expect("get_datetime");
            let temp = rtc.read_temperature().expect("read_temperature");
            println!("[minute] {:04}-{:02}-{:02} {:02}:{:02}:{:02}  {:.2} C",
                     dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, temp);
            minute_alarms += 1;
        }
        if status & SOURCE_ALARM2 != 0 {
            let dt = rtc.get_datetime().expect("get_datetime");
            println!("[hourly boundary] {:04}-{:02}-{:02} {:02}:{:02}:{:02}",
                     dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        }
        delay.delay_ms(1000);
    }

    // --- Shut the alarms back down before exiting ---
    rtc.disable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2).expect("disable_interrupt");
}
