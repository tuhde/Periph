#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Input, Output, Pull};
use esp_println::println;
use periph::chips::adc_dac::Hx711Minimal;
use periph::connection::hx711::HX711Connection;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let dout   = Input::new(peripherals.GPIO5, Pull::None);
    let pd_sck = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::Low);
    let connection = HX711Connection::new(dout, pd_sck);
    let mut delay = Delay::new();

    let mut chip = Hx711Minimal::new(connection).expect("init HX711");  // Create HX711 driver — discards first conversion, (connection) → Result<Hx711Minimal, _>

    loop {
        let ready = chip.is_ready().expect("is_ready");    // Check if conversion is ready (non-blocking), () → Result<bool, _>
        let raw = chip.read_raw().expect("read_raw");      // Read signed 24-bit ADC value, () → Result<i32, _>
        println!("{}", raw);
        delay.delay_ms(500);
    }
}
