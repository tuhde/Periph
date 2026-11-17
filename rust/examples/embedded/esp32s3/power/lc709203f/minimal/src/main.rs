#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Lc709203fMinimal;

esp_app_desc!();

const ADDR: u8 = 0x0B;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Lc709203fMinimal::new(i2c, ADDR, 1000, 1, &mut delay).expect("init LC709203F"); // Create LC709203F driver, (i2c, addr, capacity_mah, profile, delay)

    loop {
        let v = chip.voltage().expect("voltage");
        let p = chip.percent().expect("percent");
        let t = chip.temperature().expect("temperature");
        println!("V={:.3} V  SOC={:.1} %  T={:.2} C", v, p, t);   // Read V / % / °C, () → Result<f32>
        delay.delay_ms(5000);
    }
}
