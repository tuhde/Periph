#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Mcp4728Minimal;

esp_app_desc!();

const ADDR: u8 = 0x60;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut dac = Mcp4728Minimal::new(i2c, ADDR).expect("init MCP4728");

    loop {
        dac.set_voltage(0, 0.5).expect("set voltage ch0");   // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → Result<(), E>
        dac.set_raw(1, 2048).expect("set raw ch1");          // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → Result<(), E>
        dac.set_all([0.0, 0.25, 0.5, 1.0]).expect("set all"); // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
        println!("MCP4728 minimal running");
        delay.delay_ms(1000);
    }
}
