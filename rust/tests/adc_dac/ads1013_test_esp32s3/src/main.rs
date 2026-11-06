#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::{Ads1013Full, Ads1013Minimal, ADS1013_DR_1600, ADS1013_DR_250};

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

    let dev = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut minimal = Ads1013Minimal::new(dev, addr);
    let raw = minimal.read_raw(&mut delay).expect("read_raw");
    check_true!(raw >= -2048 && raw <= 2047, "raw_in_range", passed, failed);
    let v = minimal.read_voltage(&mut delay).expect("read_voltage");
    check_true!(v > -7.0 && v < 7.0, "voltage_plausible", passed, failed);
    let i2c = minimal.release();

    let mut full = Ads1013Full::new(i2c, addr);
    full.reset().expect("reset");
    check_true!(full.read_config().expect("read_config") & 0x7FFF == 0x0583, "reset_config", passed, failed);
    check_true!(full.full_scale() == 2.048 && full.lsb() == 0.001, "full_scale_fixed", passed, failed);

    full.set_data_rate(ADS1013_DR_250).expect("set_data_rate");
    check_true!(full.get_data_rate() == ADS1013_DR_250 && full.data_rate_sps() == 250, "dr_roundtrip", passed, failed);
    full.read_raw(&mut delay).expect("read_raw");
    check_true!((full.read_config().expect("read_config") >> 5) & 0x7 == ADS1013_DR_250 as u16, "config_dr_bits", passed, failed);
    full.set_data_rate(ADS1013_DR_1600).expect("set_data_rate");

    full.start_continuous().expect("start_continuous");
    delay.delay_millis(50);
    let cont = full.read_continuous_raw().expect("read_continuous_raw");
    check_true!(cont >= -2048 && cont <= 2047, "continuous_raw_in_range", passed, failed);
    check_true!(full.read_config().expect("read_config") & 0x0100 == 0, "continuous_mode_bit", passed, failed);
    full.stop_continuous().expect("stop_continuous");
    check_true!(full.read_config().expect("read_config") & 0x0100 != 0, "stop_mode_bit", passed, failed);

    full.reset().expect("reset");
    check_true!(full.read_config().expect("read_config") & 0x7FFF == 0x0583, "final_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
