#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Mcp4725Full;

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

    let mut dac = Mcp4725Full::new(i2c, ADDR).expect("init MCP4725");

    const STEP: f32 = 1.0 / 20.0;
    const DELAY_MS: u64 = 100;

    println!("MCP4725 demo: triangle wave");
    // Up sweep: 0 to full scale in 21 steps
    for n in 0..=20 {
        let fraction = n as f32 * STEP;
        dac.set_voltage(fraction).expect("set voltage");           // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
        let code = (fraction * 4095.0).round() as u16;
        let approx_v = code as f32 * 3.3 / 4096.0;
        println!("n={:2} fraction={:.2} code={:4} approx_v={:.3}V", n, fraction, code, approx_v);
        sleep(Duration::from_millis(DELAY_MS));
    }
    // Down sweep: full scale back to 0 in 20 steps
    for n in (0..=20).rev() {
        let fraction = n as f32 * STEP;
        dac.set_voltage(fraction).expect("set voltage");           // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
        let code = (fraction * 4095.0).round() as u16;
        let approx_v = code as f32 * 3.3 / 4096.0;
        println!("n={:2} fraction={:.2} code={:4} approx_v={:.3}V", n, fraction, code, approx_v);
        sleep(Duration::from_millis(DELAY_MS));
    }
    println!("Demo complete");
    loop {}
}
