#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Mcp4725Minimal;

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

    let mut dac = Mcp4725Minimal::new(i2c, ADDR).expect("init MCP4725");

    loop {
        dac.set_voltage(0.5).expect("set voltage 0.5");  // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
        dac.set_raw(2048).expect("set raw 2048");         // Set raw 12-bit code, (code=0–4095) → Result<(), E>
        println!("MCP4725 minimal running");
        delay.delay_ms(1000);
    }
}
