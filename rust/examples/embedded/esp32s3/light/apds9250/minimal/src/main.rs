//! Minimal example for the APDS-9250 — print red, green, blue and IR counts. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::{Apds9250Minimal, APDS9250_I2C_ADDRESS};

esp_app_desc!();

const ADDR: u8 = APDS9250_I2C_ADDRESS;

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

    // Create APDS-9250 driver, (i2c, addr=0x52) → Apds9250Minimal
    let mut chip = Apds9250Minimal::new(i2c, ADDR);
    // Check PART_ID and start colour mode, (delay) → Result<(), Apds9250Error>
    chip.init(&mut delay).expect("init");

    loop {
        // Read the four channels, (delay) → Result<Apds9250Counts>
        let c = chip.read(&mut delay).expect("read");
        println!("R {} G {} B {} IR {}", c.red, c.green, c.blue, c.ir);
        sleep_ms!(1000);
    }
}
