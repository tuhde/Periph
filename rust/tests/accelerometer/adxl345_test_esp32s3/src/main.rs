#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output};
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::accelerometer::Adxl345Minimal;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const SDA_PIN: u8 = 1;
const SCL_PIN: u8 = 2;
const ADDR: u8 = 0x53;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let sda = peripherals.GPIO1;
    let scl = peripherals.GPIO2;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .with_sda(sda)
        .with_scl(scl);

    let mut delay = Delay::new();
    let mut chip = Adxl345Minimal::new(i2c, ADDR, false).expect("init ADXL345");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let (x, y, z) = chip.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_returns_floats", passed, failed);
    let mag = libm::sqrtf(x * x + y * y + z * z);
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g", passed, failed);

    drop(chip);
    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .with_sda(sda)
        .with_scl(scl);
    let mut chip_full = periph::chips::accelerometer::Adxl345Full::new(i2c, ADDR, false).expect("init ADXL345 Full");
    chip_full.set_range(4).expect("set_range");
    let (x, y, z) = chip_full.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_after_set_range_4g", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 {
        loop { esp_hal::delay::Delay::new().delay_millis(1000); }
    }
    loop { esp_hal::delay::Delay::new().delay_millis(1000); }
}