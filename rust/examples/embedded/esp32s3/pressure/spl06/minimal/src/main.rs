//! Minimal example for the SPL06-007 — read temperature and pressure every second. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::Spl06Minimal;

esp_app_desc!();

const ADDR: u8 = 0x77;

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

    // Create SPL06 driver, (i2c, addr=0x77) → Spl06Minimal
    let mut chip = Spl06Minimal::new(i2c, ADDR);
    // Check ID, read coefficients, apply defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Measure temperature and pressure, (delay) → Result<Spl06Measurement {temperature_c °C, pressure_pa Pa}>
        let m = chip.read(&mut delay).expect("read");
        println!("{:.2} Pa, {:.2} C", m.pressure_pa, m.temperature_c);
        sleep_ms!(1000);
    }
}
