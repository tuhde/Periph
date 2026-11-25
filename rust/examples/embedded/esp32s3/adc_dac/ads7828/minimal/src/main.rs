#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::{Ads7828Minimal};

esp_app_desc!();

const ADDR: u8 = 0x48;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut adc = Ads7828Minimal::new(i2c, Delay::new(), ADDR, None); // Create ADS7828 driver, (i2c, delay, addr=0x48, vref None → internal 2.5 V)
    adc.init().expect("init");                                  // Enable internal reference, () → Result<(), E>

    loop {
        let raw = adc.read_raw(0).expect("read_raw");           // Read CH0 raw code, (channel=0–7) → Result<u16, E>
        let v0 = adc.read_voltage(0).expect("read_voltage");    // Read CH0 voltage, (channel=0–7) → Result<f32, E> V
        let all = adc.read_all().expect("read_all");            // Read all eight channels, () → Result<[f32; 8], E> V
        println!("raw={} v0={:.3} V ch7={:.3} V", raw, v0, all[7]);
        delay.delay_ms(1000);
    }
}
