#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::AM2315Minimal;

esp_app_desc!();

const ADDR: u8 = 0x5C;

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

    // Create AM2315 driver, (i2c, ADDR=0x5C) → AM2315
    let mut chip = AM2315Minimal::new(i2c, ADDR);

    loop {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
        // (the driver waits 2 s before every transaction; the result is the previous measurement)
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.1} C, {:.1} %RH", t, rh);
    }
}
