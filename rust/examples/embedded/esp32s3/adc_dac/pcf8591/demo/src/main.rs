#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::pcf8591::MODE_4_SINGLE_ENDED;
use periph::chips::adc_dac::Pcf8591Full;

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

    let mut adc = Pcf8591Full::new(i2c, ADDR).expect("init PCF8591");

    const VREF: f32  = 3.3;
    const VAGND: f32 = 0.0;

    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    adc.configure(MODE_4_SINGLE_ENDED, false, true)                     // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → Result<(), E>
        .expect("configure");                                            // single-ended mode with DAC output enabled
    loop {
        for n in 0..20 {
            let raw = adc.read_channel(0).expect("read ch0");            // Read single channel, (channel=0–3) → Result<u8, E>
            let vin  = VAGND + (raw as f32) * (VREF - VAGND) / 256.0;
            adc.set_dac(raw).expect("set_dac");                          // Enable DAC and set raw value, (value=0–255) → Result<(), E>
            let vout = VAGND + (raw as f32) * (VREF - VAGND) / 256.0;
            println!("n={:2} raw={:3} vin={:.3}V  vout={:.3}V", n, raw, vin, vout);
            delay.delay_ms(200);
        }
    }
}
