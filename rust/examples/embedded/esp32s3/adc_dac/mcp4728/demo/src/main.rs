#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Mcp4728Full;

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

    let mut dac = Mcp4728Full::new(i2c, ADDR).expect("init MCP4728");

    const VDD: f32 = 3.3;
    const STEP: f32 = 1.0 / 16.0;
    const DELAY_MS: u64 = 50;

    println!("MCP4728 demo: four-point calibration and synchronous staircase");

    // --- Apply four-point calibration voltages to channels A–D ---
    // A 4-channel DAC is the canonical way to bias a 4-point sensor bridge
    // (load cell, RTD conditioning, strain gauge). Each channel gets a
    // different fraction of full scale to demonstrate independent outputs.
    // Voltages printed below assume a 3.3 V supply.
    let calibration: [f32; 4] = [0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0];
    dac.set_all(calibration).expect("set all calibration");  // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
    for ch in 0..4 {
        let code = (calibration[ch] * 4095.0).round() as u16;
        let approx_v = code as f32 * VDD / 4096.0;
        println!("ch={} fraction={:.4} code={:4} approx_v={:.3}V",
                 ch, calibration[ch], code, approx_v);
    }
    delay.delay_ms(500);

    // --- Synchronous staircase from 0 to full scale on all four channels ---
    // Using set_all with the same fraction across channels keeps them in lock-step
    // and demonstrates simultaneous V_OUT update via Fast Write. A 50 ms pause
    // between steps lets the host controller observe each level on the scope.
    for n in 0..=16u32 {
        let f = n as f32 * STEP;
        dac.set_all([f, f, f, f]).expect("set all step");   // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
        let code = (f * 4095.0).round() as u16;
        let approx_v = code as f32 * VDD / 4096.0;
        println!("step={:2} fraction={:.4} code={:4} approx_v={:.3}V",
                 n, f, code, approx_v);
        sleep(Duration::from_millis(DELAY_MS));
    }

    // --- Reset all channels to 0 V before exit ---
    dac.set_all([0.0, 0.0, 0.0, 0.0]).expect("set all zero");  // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
    println!("Demo complete");
    loop {}
}
