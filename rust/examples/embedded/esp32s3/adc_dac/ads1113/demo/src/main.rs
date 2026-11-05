#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::ads1113::{ADS1113_DR_8, ADS1113_I2C_ADDRESS};
use periph::chips::adc_dac::Ads1113Full;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let dev = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
let mut adc = Ads1113Full::new(dev, ADS1113_I2C_ADDRESS);                                       // Create ADS1113 Full driver, (i2c, addr=0x48)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    adc.set_data_rate(ADS1113_DR_8).expect("set_data_rate");                                    // Set data rate, (dr 0–7) → Result<(), E>

    // --- Log single-shot readings ---
    // Each read_voltage() wakes the chip, converts and powers down again.
    for _ in 0..5 {
        let v = adc.read_voltage(&mut delay).expect("read_voltage");                            // Read voltage, (delay) → Result<f32 V, E>
        println!("{:.5} V", v);
        delay.delay_millis(1000);
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.start_continuous().expect("start_continuous");                                          // Start continuous, () → Result<(), E>
    delay.delay_millis(200);
    let mut acc = 0.0f32;
    for _ in 0..10 {
        acc += adc.read_continuous().expect("read_continuous");                                 // Read continuous, () → Result<f32 V, E>
        delay.delay_millis(125);
    }
    adc.stop_continuous().expect("stop_continuous");                                            // Stop continuous, () → Result<(), E>
    println!("average {:.5} V", acc / 10.0);

    loop {
        delay.delay_millis(1000);
    }
}
