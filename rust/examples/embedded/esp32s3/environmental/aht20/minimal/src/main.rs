#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Aht20Minimal;

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Aht20Minimal::new(i2c, ADDR, &mut delay).expect("init AHT20");  // Create AHT20 driver, (i2c, ADDR=0x38, delay) → Result

    loop {
        let (t, h) = chip.read(&mut delay).expect("read");                         // Trigger measurement, (delay) → (f32 °C, f32 %RH)
        println!("T={:.2} C  H={:.2} %RH", t, h);
        delay.delay_ms(1000);
    }
}
