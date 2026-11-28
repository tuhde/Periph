//! RRH62000 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x69.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::gas::rrh62000;
use periph::chips::gas::Rrh62000Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = Rrh62000Full::new(i2c, Delay::new(), rrh62000::ADDRESS);

    esp_println::println!("RRH62000 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init().expect("init");
    check_true!(chip.firmware_version().is_ok(), "firmware_version");
    check_true!(chip.algorithm_version().is_ok(), "algorithm_version");
    check_true!(chip.unique_id().is_ok(), "unique_id");
    let r = chip.read();
    check_true!(r.is_ok(), "read");
    if let Ok(m) = r {
        check_true!(m.pm2_5_ug_m3 >= 0.0 && m.pm2_5_ug_m3 <= 1000.0, "read_pm");
        check_true!(m.temperature_c >= -20.0 && m.temperature_c <= 60.0, "read_temperature");
        check_true!(m.humidity_rh >= 0.0 && m.humidity_rh <= 100.0, "read_humidity");
        check_true!(m.iaq >= 0.0 && m.iaq <= 5.0, "read_iaq");
    }
    check_true!(chip.read_all().map(|d| d.particles.nc_0_3 >= d.particles.nc_0_5).unwrap_or(false), "read_all");
    check_true!(chip.read_status().map(|s| !s.fan).unwrap_or(false), "read_status");
    check_true!(chip.read_particles().is_ok(), "read_particles");
    check_true!(chip.data_ready().is_ok(), "data_ready");
    let avg = chip.get_moving_average().unwrap_or(10);
    check_true!(chip.set_moving_average(20).is_ok() && chip.get_moving_average().ok() == Some(20), "moving_average");
    chip.set_moving_average(avg).ok();
    let fan = chip.get_fan_speed().unwrap_or(86);
    check_true!(chip.set_fan_speed(70).is_ok() && chip.get_fan_speed().ok() == Some(70), "fan_speed");
    chip.set_fan_speed(fan).ok();
    let interval = chip.get_cleaning_interval().unwrap_or(604_800);
    check_true!(chip.set_cleaning_interval(86_400).is_ok() && chip.get_cleaning_interval().ok() == Some(86_400), "cleaning_interval");
    chip.set_cleaning_interval(interval).ok();
    let t = chip.get_cleaning_time().unwrap_or(10);
    check_true!(chip.set_cleaning_time(12).is_ok() && chip.get_cleaning_time().ok() == Some(12), "cleaning_time");
    chip.set_cleaning_time(t).ok();
    check_true!(chip.read_mox_resistance().map(|r| r > 0).unwrap_or(false), "mox_resistance");
    check_true!(chip.cleaning_done().is_ok(), "cleaning_done");
    check_true!(chip.sleep().is_ok(), "sleep");
    sleep_ms!(3000);
    check_true!(chip.wake().is_ok() && chip.read().is_ok(), "wake_read");
    check_true!(chip.soft_reset().is_ok() && chip.read().is_ok(), "soft_reset_read");
    sleep_ms!(10);

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
