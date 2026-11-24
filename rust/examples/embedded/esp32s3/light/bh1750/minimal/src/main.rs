//! Minimal example for the BH1750 — measure the illuminance once per second. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::BH1750Minimal;

esp_app_desc!();

const ADDR: u8 = 0x23;

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

    // Create BH1750 driver, (i2c, addr=0x23) → BH1750
    let mut chip = BH1750Minimal::new(i2c, ADDR);
    // Power on, () → Result<()>
    chip.init().expect("init");

    loop {
        // Measure illuminance, (delay) → Result<f32 lx>
        let lux = chip.read(&mut delay).expect("read");
        println!("{:.1} lx", lux);
        sleep_ms!(1000);
    }
}
