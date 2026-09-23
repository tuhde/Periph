//! L3GD20H hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO4 -> SDA, GPIO5 -> SCL; SA0 low (address 0x6A).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::gyroscope::{L3gd20hFull, L3GD20H_FS_500_DPS, ODR_190_HZ};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO4)
        .with_scl(peripherals.GPIO5);

    // L3gd20hFull carries the Minimal API too, so one driver covers both stages.
    let mut gyro = L3gd20hFull::new(i2c, 0x6A, false).expect("init L3GD20H");
    // CTRL_REG1 power-up needs ~250 ms before the first valid sample.
    Delay::new().delay_millis(300);

    let (x, y, z) = gyro.gyro().expect("read gyro");
    check_true!(!(x.is_nan() || y.is_nan() || z.is_nan()), "gyro_returns_valid_floats", passed, failed);
    // At rest every axis should be well under 1 rad/s.
    check_true!(x.abs() < 1.0 && y.abs() < 1.0 && z.abs() < 1.0, "gyro_at_rest", passed, failed);

    check_true!(gyro.configure(ODR_190_HZ, 0, L3GD20H_FS_500_DPS).is_ok(), "configure_ok", passed, failed);
    check_true!(gyro.temperature().is_ok(), "temperature_ok", passed, failed);
    check_true!(gyro.data_ready().is_ok(), "data_ready_ok", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
