#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gas::Ens160Minimal;

esp_app_desc!();

const ADDR: u8 = 0x53;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut sensor = Ens160Minimal::new(i2c, ADDR).expect("init ENS160"); // Create ENS160 driver, (i2c, ADDR=0x52)

    println!("Waiting for sensor warm-up...");
    loop {                                                    // Wait for valid data, () → blocks until warm
        let status = sensor.wait_for_new_data(2000).unwrap();
        if (status >> 2) & 0x03 == 0 { break; }
    }

    for _ in 0..10 {
        let (aqi, tvoc_ppb, eco2_ppm) = sensor.read_air_quality().expect("read air quality");  // Read air quality, () → (u8, f32, f32)
        println!("AQI={} TVOC={} ppb eCO2={} ppm", aqi, tvoc_ppb, eco2_ppm);
        delay.delay_ms(1000);
    }
}
