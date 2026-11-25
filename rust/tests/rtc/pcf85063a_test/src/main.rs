use linux_embedded_hal::I2cdev;
use periph::chips::rtc::{
    Pcf85063aAlarm, Pcf85063aDateTime, Pcf85063aFull, Pcf85063aMinimal, Pcf85063aOffsetMode, Pcf85063aSourceClock,
    PCF85063A_SOURCE_TIMER,
};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = 0x51;

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Pcf85063aMinimal::new(dev, addr).expect("init PCF85063A");
    let before = minimal.get_datetime().expect("get_datetime");
    check_true!(before.month >= 1 && before.month <= 12, "minimal_get_datetime", passed, failed);
    drop(minimal);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Pcf85063aFull::new(dev, addr).expect("init PCF85063A Full");

    let target = Pcf85063aDateTime { year: 2026, month: 9, day: 23, weekday: 3, hour: 14, minute: 30, second: 0 };
    rtc.set_datetime(target).expect("set_datetime");
    let dt = rtc.get_datetime().expect("get_datetime");
    check_true!(dt.year == 2026 && dt.month == 9 && dt.day == 23 && dt.weekday == 3, "get_datetime_roundtrip_date", passed, failed);
    check_true!(dt.hour == 14 && dt.minute == 30, "get_datetime_roundtrip_time", passed, failed);
    check_true!(!rtc.oscillator_stopped().expect("oscillator_stopped"), "oscillator_running_after_set_datetime", passed, failed);

    let alarm = Pcf85063aAlarm { second: Some(0), minute: Some(15), hour: Some(6), day: None, weekday: None };
    rtc.set_alarm(alarm).expect("set_alarm");
    check_true!(rtc.get_alarm().expect("get_alarm") == alarm, "alarm_roundtrip", passed, failed);
    rtc.set_alarm(Pcf85063aAlarm::default()).expect("set_alarm");

    rtc.set_offset(-3, Pcf85063aOffsetMode::Course).expect("set_offset");
    check_true!(rtc.get_offset().expect("get_offset") == (-3, Pcf85063aOffsetMode::Course), "offset_roundtrip", passed, failed);
    rtc.set_offset(0, Pcf85063aOffsetMode::Normal).expect("set_offset");

    rtc.write_ram(0xA5).expect("write_ram");
    check_true!(rtc.read_ram().expect("read_ram") == 0xA5, "ram_roundtrip", passed, failed);

    // Countdown timer at 64 Hz from 64 counts expires after ~1 s.
    rtc.poll_interrupt().expect("poll_interrupt");
    rtc.configure_timer(64, Pcf85063aSourceClock::Hz64, false).expect("configure_timer");
    std::thread::sleep(std::time::Duration::from_millis(1500));
    let status = rtc.poll_interrupt().expect("poll_interrupt");
    check_true!(status & PCF85063A_SOURCE_TIMER != 0, "timer_fires", passed, failed);
    rtc.disable_timer().expect("disable_timer");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
