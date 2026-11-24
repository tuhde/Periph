//! Minimal example for the Si1132 — read temperature and light every 2 s. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Si1132Minimal;

esp_app_desc!();

const ADDR: u8 = 0x60;

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

    // Create Si1132 driver, (i2c, addr=0x60) → Si1132
    let mut chip = Si1132Minimal::new(i2c, ADDR);
    // Verify PART_ID and start forced mode, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read UV index and ALS counts, (delay) → Result<Reading {uv_index: f32, visible: u16, infrared: u16}>
        let r = chip.read(&mut delay).expect("read");
        println!("UV {:.2}, visible {}, infrared {}", r.uv_index, r.visible, r.infrared);
        sleep_ms!(1000);
    }
}
