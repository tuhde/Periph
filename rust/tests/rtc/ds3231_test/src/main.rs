use linux_embedded_hal::{I2cdev, Delay};
use periph::chips::rtc::{Ds3231Minimal, Ds3231Full, DateTime, Alarm1Match, SOURCE_ALARM1};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = 0x68;

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Ds3231Minimal::new(dev, addr).expect("init DS3231");

    let target = DateTime { year: 2026, month: 9, day: 22, weekday: 2, hour: 14, minute: 30, second: 0 };
    rtc.set_datetime(target).expect("set_datetime");
    let dt = rtc.get_datetime().expect("get_datetime");
    check_true!(dt == target, "get_datetime_roundtrip", passed, failed);

    let temp = rtc.read_temperature().expect("read_temperature");
    check_true!(temp > -40.0 && temp < 85.0, "temperature_in_range", passed, failed);

    drop(rtc);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc_full = Ds3231Full::new(dev, addr).expect("init DS3231 Full");

    rtc_full.set_alarm1(0, 0, 0, 0, Alarm1Match::EverySecond).expect("set_alarm1");
    let (_, _, _, _, mode) = rtc_full.get_alarm1().expect("get_alarm1");
    check_true!(mode == Alarm1Match::EverySecond, "alarm1_roundtrip", passed, failed);

    rtc_full.enable_interrupt(SOURCE_ALARM1).expect("enable_interrupt");
    std::thread::sleep(std::time::Duration::from_millis(1100));
    let status = rtc_full.poll_interrupt().expect("poll_interrupt");
    check_true!(status & SOURCE_ALARM1 != 0, "alarm1_fires_every_second", passed, failed);
    rtc_full.disable_interrupt(SOURCE_ALARM1).expect("disable_interrupt");

    let mut delay = Delay;
    rtc_full.force_temperature_conversion(&mut delay).expect("force_temperature_conversion");
    let temp2 = rtc_full.read_temperature().expect("read_temperature");
    check_true!(temp2 > -40.0 && temp2 < 85.0, "forced_temperature_in_range", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
