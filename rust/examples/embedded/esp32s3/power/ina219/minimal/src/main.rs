#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Ina219Minimal;

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

    let mut chip = Ina219Minimal::new(i2c, ADDR, 0.1, 2.0).expect("init INA219");

    loop {
        let v = chip.voltage().expect("voltage");    // Read bus voltage, () → f32 V
        let i = chip.current().expect("current");     // Read load current, () → f32 A
        let p = chip.power().expect("power");         // Read power, () → f32 W
        println!("V={:.3}V  I={:.6}A  P={:.6}W", v, i, p);
        delay.delay_ms(1000);
    }
}
