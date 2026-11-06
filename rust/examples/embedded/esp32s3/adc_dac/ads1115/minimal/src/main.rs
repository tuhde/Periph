#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::ads1115::{ADS1115_I2C_ADDRESS};
use periph::chips::adc_dac::Ads1115Minimal;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let dev = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
let mut adc = Ads1115Minimal::new(dev, ADS1115_I2C_ADDRESS);                                    // Create ADS1115 driver, (i2c, addr=0x48)

    loop {
        let v = adc.read_voltage(0, &mut delay).expect("read_voltage");                         // Read voltage, (channel=0, delay) → Result<f32 V, E>
        println!("{:.5} V", v);
        delay.delay_millis(1000);
    }

    loop {
        delay.delay_millis(1000);
    }
}
