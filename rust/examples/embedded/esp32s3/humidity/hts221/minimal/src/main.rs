//! Minimal example for the HTS221 — read temperature and humidity every 2 s. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HTS221Minimal;

esp_app_desc!();

const ADDR: u8 = 0x5F;

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

    // Create HTS221 driver, (i2c, addr=0x5F) → HTS221
    let mut chip = HTS221Minimal::new(i2c, ADDR);
    // Read the calibration and start one-shot mode, () → Result<()>
    chip.init().expect("init");

    loop {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %rH)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C, {:.1} %rH", t, rh);
        sleep_ms!(2000);
    }
}
