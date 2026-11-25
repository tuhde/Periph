#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::rtc::{Pcf85063aAlarm, Pcf85063aDateTime, Pcf85063aFull, Pcf85063aOffsetMode, Pcf85063aSourceClock, PCF85063A_SOURCE_TIMER};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x51;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut rtc = Pcf85063aFull::new(i2c, ADDR).expect("init PCF85063A");

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
    Delay::new().delay_millis(1500);
    let status = rtc.poll_interrupt().expect("poll_interrupt");
    check_true!(status & PCF85063A_SOURCE_TIMER != 0, "timer_fires", passed, failed);
    rtc.disable_timer().expect("disable_timer");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop { Delay::new().delay_millis(1000); }
}
