#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::rtc::{OffsetMode, Pcf8523Alarm, Pcf8523DateTime, Pcf8523Full, SourceClock, PCF8523_SOURCE_TIMER_B};

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
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut rtc = Pcf8523Full::new(i2c, ADDR).expect("init PCF8523");

    let target = Pcf8523DateTime { year: 2026, month: 9, day: 23, weekday: 3, hour: 14, minute: 30, second: 0 };
    rtc.set_datetime(target).expect("set_datetime");
    let dt = rtc.get_datetime().expect("get_datetime");
    check_true!(dt.year == 2026 && dt.month == 9 && dt.day == 23 && dt.weekday == 3, "get_datetime_roundtrip_date", passed, failed);
    check_true!(dt.hour == 14 && dt.minute == 30, "get_datetime_roundtrip_time", passed, failed);
    check_true!(!rtc.oscillator_stopped().expect("oscillator_stopped"), "oscillator_running_after_set_datetime", passed, failed);

    let alarm = Pcf8523Alarm { minute: Some(15), hour: Some(6), day: None, weekday: None };
    rtc.set_alarm(alarm).expect("set_alarm");
    check_true!(rtc.get_alarm().expect("get_alarm") == alarm, "alarm_roundtrip", passed, failed);
    rtc.set_alarm(Pcf8523Alarm::default()).expect("set_alarm");

    rtc.set_offset(-3, OffsetMode::EveryMinute).expect("set_offset");
    check_true!(rtc.get_offset().expect("get_offset") == (-3, OffsetMode::EveryMinute), "offset_roundtrip", passed, failed);
    rtc.set_offset(0, OffsetMode::EveryTwoHours).expect("set_offset");

    // Timer B at 64 Hz from 64 counts expires after ~1 s.
    rtc.poll_interrupt().expect("poll_interrupt");
    rtc.configure_timer_b(64, SourceClock::Hz64, 46.875, false).expect("configure_timer_b");
    Delay::new().delay_millis(1500);
    let status = rtc.poll_interrupt().expect("poll_interrupt");
    check_true!(status & PCF8523_SOURCE_TIMER_B != 0, "timer_b_fires", passed, failed);
    rtc.disable_timer_b().expect("disable_timer_b");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop { Delay::new().delay_millis(1000); }
}
