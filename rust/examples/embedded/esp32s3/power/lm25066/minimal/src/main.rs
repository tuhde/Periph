#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Lm25066Minimal;

esp_app_desc!();

const ADDR: u8 = 0x40;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Lm25066Minimal::new(i2c, ADDR, 1.0, false); // Create LM25066 driver, (i2c, addr, r_sense_mohm, current_limit_high)

    loop {
        println!("VIN={:.3} V  VOUT={:.3} V", chip.voltage_in().unwrap(), chip.voltage_out().unwrap()); // Read VIN / VOUT, () → Result<f32>
        println!("IIN={:.3} A  PIN={:.2} W  T={:.1} C", chip.current().unwrap(), chip.power().unwrap(), chip.temperature().unwrap()); // Read A / W / MOSFET °C, () → Result<f32>
        delay.delay_ms(1000);
    }
}
