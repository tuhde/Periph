#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Input, Output, Pull};
use esp_println::println;
use periph::chips::adc_dac::Hx711Full;
use periph::transport::hx711::HX711Transport;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let dout   = Input::new(peripherals.GPIO5, Pull::None);
    let pd_sck = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::Low);
    let transport = HX711Transport::new(dout, pd_sck);
    let mut delay = Delay::new();

    let mut chip = Hx711Full::new(transport).expect("init HX711");  // Create HX711 driver — discards first conversion, (transport) → Result<Hx711Full, _>

    println!("Taring — keep scale empty...");
    chip.tare(10).expect("tare");                                   // Capture zero offset from 10-reading average, (times: u8) → Result<(), _>
    chip.set_scale(SCALE_FACTOR);                                   // Set calibration scale factor, (factor: f32) → ()
    println!("Tare done. Place weight on scale.");

    let mut prev_weight: Option<f32> = None;
    loop {
        let weight = chip.read_weight(3).expect("read_weight");     // Return calibrated weight, (times: u8) → Result<f32, _>
        let rounded = (weight * 10.0).round() / 10.0;
        if prev_weight.map_or(true, |p| (rounded - p).abs() > 1.0) {
            println!("→ {:.1} g", rounded);
            prev_weight = Some(rounded);
        }
        delay.delay_ms(500);
    }
}
