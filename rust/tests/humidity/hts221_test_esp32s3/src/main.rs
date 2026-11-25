//! HTS221 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x5F.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::humidity::HTS221Full;

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
    let mut chip = HTS221Full::new(i2c, 0x5F);

    esp_println::println!("HTS221 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init().expect("init");
    check_true!(chip.who_am_i().expect("who_am_i") == 0xBC, "who_am_i");
    let cal = chip.read_calibration();
    check_true!(cal.h1_rh > cal.h0_rh && cal.t1_degc > cal.t0_degc, "calibration_plausible");
    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_range");
    check_true!(rh >= 0.0 && rh <= 100.0, "read_humidity_range");
    let h = chip.read_humidity(&mut delay).expect("rh");
    check_true!(h >= 0.0 && h <= 100.0, "read_humidity_single");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature_single");
    let dew = chip.dew_point(&mut delay).expect("dew");
    check_true!(dew <= t + 1.0, "dew_point_below_temperature");
    chip.set_averaging(32, 64).expect("averaging");
    check_true!(chip.get_averaging().expect("get averaging") == (32, 64), "averaging_roundtrip");
    chip.set_averaging(16, 32).expect("averaging");
    chip.set_output_data_rate(1.0).expect("odr");
    check_true!(chip.get_output_data_rate().expect("get odr") == 1.0, "odr_roundtrip");
    sleep_ms!(1500);
    let (_, rhc) = chip.read_continuous().expect("continuous");
    check_true!(rhc >= 0.0 && rhc <= 100.0, "continuous_read");
    chip.set_output_data_rate(0.0).expect("odr");
    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("is_heater_on"), "heater_on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("is_heater_on"), "heater_off");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
