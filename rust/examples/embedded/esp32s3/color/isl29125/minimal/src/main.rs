//! Minimal example for the ISL29125 — print red, green and blue counts.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::color::{Isl29125Minimal, ISL29125_I2C_ADDRESS};

esp_app_desc!();

const ADDR: u8 = ISL29125_I2C_ADDRESS;

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

    // Create ISL29125 driver, (i2c, addr=0x44) → Isl29125Minimal
    let mut chip = Isl29125Minimal::new(i2c, ADDR);
    // Check DEVICE_ID and start RGB conversion, () → Result<(), Isl29125Error>
    chip.init().expect("init");

    loop {
        // Read the three channels, (delay) → Result<Isl29125Counts>
        let c = chip.read(&mut delay).expect("read");
        println!("R {} G {} B {}", c.red, c.green, c.blue);
        sleep_ms!(1000);
    }
}
