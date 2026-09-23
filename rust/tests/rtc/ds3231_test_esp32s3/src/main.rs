#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::rtc::{Ds3231Full, DateTime, Alarm1Match, SOURCE_ALARM1};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x68;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    // Ds3231Full carries the whole Minimal API, so one driver covers both
    // stages without re-claiming the I2C peripheral.
    let mut rtc_full = Ds3231Full::new(i2c, ADDR).expect("init DS3231 Full");

    let target = DateTime { year: 2026, month: 9, day: 22, weekday: 2, hour: 14, minute: 30, second: 0 };
    rtc_full.set_datetime(target).expect("set_datetime");
    let dt = rtc_full.get_datetime().expect("get_datetime");
    check_true!(dt == target, "get_datetime_roundtrip", passed, failed);

    let temp = rtc_full.read_temperature().expect("read_temperature");
    check_true!(temp > -40.0 && temp < 85.0, "temperature_in_range", passed, failed);

    rtc_full.set_alarm1(0, 0, 0, 0, Alarm1Match::EverySecond).expect("set_alarm1");
    let (_, _, _, _, mode) = rtc_full.get_alarm1().expect("get_alarm1");
    check_true!(mode == Alarm1Match::EverySecond, "alarm1_roundtrip", passed, failed);

    rtc_full.enable_interrupt(SOURCE_ALARM1).expect("enable_interrupt");
    Delay::new().delay_millis(1100);
    let status = rtc_full.poll_interrupt().expect("poll_interrupt");
    check_true!(status & SOURCE_ALARM1 != 0, "alarm1_fires_every_second", passed, failed);
    rtc_full.disable_interrupt(SOURCE_ALARM1).expect("disable_interrupt");

    let mut delay = Delay::new();
    rtc_full.force_temperature_conversion(&mut delay).expect("force_temperature_conversion");
    let temp2 = rtc_full.read_temperature().expect("read_temperature");
    check_true!(temp2 > -40.0 && temp2 < 85.0, "forced_temperature_in_range", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop { Delay::new().delay_millis(1000); }
}
