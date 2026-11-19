//! HS30xx hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; fixed address 0x44.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::humidity::HS30xxFull;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = HS30xxFull::new(i2c, 0x44);

    esp_println::println!("HS30xx test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t >= -40.0 && t <= 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") >= -40.0, "read_temperature range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    let (rh_raw, t_raw, status) = chip.read_raw(&mut delay).expect("raw");
    check_true!(rh_raw <= 16383 && t_raw <= 16383, "raw ranges");
    check_true!(status == 0, "raw status valid");

    chip.set_measurement_wait_us(60_000).expect("wait");
    check_true!(chip.read_humidity(&mut delay).is_ok(), "read with 60 ms wait");
    chip.set_measurement_wait_us(40_000).expect("wait");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");

    // NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
