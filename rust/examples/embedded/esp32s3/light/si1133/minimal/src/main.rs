//! Minimal example for the Si1133 — print the UV and white counts once per second. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Si1133Minimal;

esp_app_desc!();

const ADDR: u8 = 0x55;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create Si1133 driver, (i2c, addr=0x55) → Si1133Minimal
    let mut chip = Si1133Minimal::new(i2c, ADDR);
    // Check PART_ID, reset, UV + white channels, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Forced measurement, (delay) → Result<Reading {uv counts, white counts}>
        let m = chip.read(&mut delay).expect("read");
        println!("UV {}, white {}", m.uv, m.white);
        sleep_ms!(1000);
    }
}
