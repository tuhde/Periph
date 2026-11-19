#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::Mpl115a2Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x60;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut baro = match Mpl115a2Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach MPL115A2 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let p_ok = baro.read_pressure(&mut delay).map(|p| p >= 50000.0 && p <= 115000.0).unwrap_or(false);
    check_true!(p_ok, "pressure_range", passed, failed);
    let t_ok = baro.read_temperature(&mut delay).map(|t| t >= -40.0 && t <= 105.0).unwrap_or(false);
    check_true!(t_ok, "temperature_range", passed, failed);
    let raw_ok = baro.read_raw(&mut delay).map(|(p, t)| p <= 1023 && t <= 1023).unwrap_or(false);
    check_true!(raw_ok, "raw_range", passed, failed);
    let both_ok = baro.read(&mut delay).map(|(p, t)| p >= 50000.0 && p <= 115000.0 && t >= -40.0 && t <= 105.0).unwrap_or(false);
    check_true!(both_ok, "read_both", passed, failed);
    let hpa_ok = baro.pressure_hpa(&mut delay).map(|h| h >= 500.0 && h <= 1150.0).unwrap_or(false);
    check_true!(hpa_ok, "pressure_hpa", passed, failed);
    let alt_ok = baro.altitude(101325.0, &mut delay).map(|a| a >= -1000.0 && a <= 6000.0).unwrap_or(false);
    check_true!(alt_ok, "altitude", passed, failed);
    let coef_ok = baro.reload_coefficients().is_ok() && baro.coefficients().0 != 0.0;
    check_true!(coef_ok, "coefficients", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
