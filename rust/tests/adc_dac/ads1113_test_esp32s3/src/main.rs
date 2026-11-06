#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::ads1113::{ADS1113_DR_860};
use periph::chips::adc_dac::{Ads1113Full, Ads1113Minimal};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x48;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();
    let addr = ADDR;

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut minimal = Ads1113Minimal::new(i2c, addr);
    let raw = minimal.read_raw(&mut delay).expect("read_raw");
    let raw2 = minimal.read_raw(&mut delay).expect("read_raw");
    check_true!((raw as i32 - raw2 as i32).abs() < 4000, "read_raw_stable", passed, failed);
    let v = minimal.read_voltage(&mut delay).expect("read_voltage");
    check_true!((v - raw as f32 * 0.0000625).abs() < 0.25, "read_voltage_matches_raw", passed, failed);
    let i2c = minimal.release();

    let mut full = Ads1113Full::new(i2c, addr);
    full.set_data_rate(ADS1113_DR_860).expect("set_data_rate");
    check_true!(full.get_data_rate() == 7 && full.data_rate_sps() == 860, "data_rate_roundtrip", passed, failed);
    full.start_continuous().expect("start_continuous");
    delay.delay_millis(50);
    let cont = full.read_continuous_raw().expect("read_continuous_raw");
    check_true!((cont as i32 - raw as i32).abs() < 8000, "continuous_matches_single_shot", passed, failed);
    check_true!(full.read_config().expect("read_config") & 0x0100 == 0, "continuous_config_mode", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    check_true!(full.read_config().expect("read_config") & 0x0100 != 0, "single_shot_config_mode", passed, failed);
    full.reset().expect("reset");
    check_true!(full.read_config().expect("read_config") & 0x7FFF == 0x0583, "reset_config", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
