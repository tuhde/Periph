#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Ina3221Minimal;

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

    let mut chip = Ina3221Minimal::new(i2c, ADDR, 0.1);  // Create INA3221 driver, (i2c, ADDR, r_shunt=0.1 Ω)

    loop {
        for ch in 1..=3 {
            let v = chip.voltage(ch).expect("voltage");  // Read bus voltage, (channel) → f32 V
            let i = chip.current(ch).expect("current");   // Read load current, (channel) → f32 A
            let p = chip.power(ch).expect("power");      // Read power, (channel) → f32 W
            println!("ch{}: {:.3}V {:.4}A {:.4}W ", ch, v, i, p);
        }
        println!();
        delay.delay_ms(1000);
    }
}
