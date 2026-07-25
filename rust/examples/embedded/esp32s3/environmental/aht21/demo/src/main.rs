#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Aht21Full;

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Aht21Full::new(i2c, ADDR, &mut delay).expect("init AHT21");      // Create AHT21 driver, (i2c, ADDR=0x38, delay) → Result

    // --- Verify calibration before starting the logging session ---
    // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during new().
    println!("Calibrated: {}", chip.is_calibrated().unwrap());                      // Check calibration status, () → Result<bool>

    println!("{:<8} {:<10} {:<10} {:<10}", "Time", "T (C)", "RH (%)", "Dew (C)");
    for n in 0..60 {
        // --- Each reading requires an 80 ms measurement cycle ---
        // The sensor cannot output data faster than this; the driver
        // handles the trigger + wait internally.
        let (t, h, crc_ok) = chip.read_with_crc(&mut delay).unwrap();               // Read with CRC verification, (delay) → Result<(f32 °C, f32 %RH, bool)>
        if !crc_ok {
            println!("CRC error at sample {}", n);
            delay.delay_ms(5000);
            continue;
        }

        // --- Magnus formula dew-point approximation ---
        // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
        // dew_point = (243.04 * gamma) / (17.625 - gamma)
        // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
        let gamma = (h / 100.0).ln() + (17.625 * t) / (243.04 + t);
        let dew = (243.04 * gamma) / (17.625 - gamma);

        println!("{:<8} {:<10.2} {:<10.2} {:<10.2}", n, t, h, dew);
        delay.delay_ms(5000);
    }
    loop {}
}
