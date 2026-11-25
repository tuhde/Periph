//! Minimal example for the MPL3115A2 — read pressure and temperature every 2 s. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::MPL3115A2Minimal;

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

    // Create MPL3115A2 driver, (i2c, addr=0x60) → MPL3115A2
    let mut chip = MPL3115A2Minimal::new(i2c, ADDR);
    // Check the chip and apply the defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read pressure and temperature, (delay) → Result<(f32 Pa, f32 °C)>
        let (p, t) = chip.read(&mut delay).expect("read");
        println!("{:.2} Pa, {:.2} C", p, t);
        sleep_ms!(2000);
    }
}
