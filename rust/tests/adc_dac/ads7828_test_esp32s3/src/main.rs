#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::{Ads7828Full, ADS7828_REF_OFF_ADC_ON, ADS7828_REF_ON_ADC_ON};

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
    let delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut adc = Ads7828Full::new(i2c, Delay::new(), ADDR, None);
    adc.init().expect("init");

    let raw = adc.read_raw(0).expect("read");
    check_true!(raw <= 4095, "read_raw_in_range", passed, failed);
    check_true!(adc.read_raw(99).expect("read") <= 4095, "read_raw_clamped", passed, failed);
    let v = adc.read_voltage(1).expect("read");
    check_true!((0.0..=2.5).contains(&v), "read_voltage_in_range", passed, failed);
    let all = adc.read_all().expect("read_all");
    check_true!(all.iter().all(|&x| (0.0..=2.5).contains(&x)), "read_all_in_range", passed, failed);
    check_true!(adc.read_differential_raw(0, false).expect("diff") <= 4095, "read_differential_raw_in_range", passed, failed);
    check_true!(adc.read_differential(1, true).expect("diff") >= 0.0, "read_differential_inverted_nonneg", passed, failed);
    let sv = adc.read_signed_differential(2).expect("signed");
    check_true!((-2.5..=2.5).contains(&sv), "read_signed_differential_in_range", passed, failed);
    check_true!((adc.lsb() - 2.5 / 4096.0).abs() < 1e-6, "lsb", passed, failed);
    check_true!(adc.uses_internal_reference() && adc.get_reference() == 2.5, "internal_reference", passed, failed);
    let mut scan = [0u16; 3];
    adc.read_scan(&[0, 3, 7], &mut scan).expect("scan");
    check_true!(scan.iter().all(|&r| r <= 4095), "read_scan_in_range", passed, failed);
    check_true!(adc.set_power_mode(ADS7828_REF_ON_ADC_ON).is_ok(), "set_power_mode_ok", passed, failed);
    check_true!(adc.set_power_mode(ADS7828_REF_OFF_ADC_ON).is_err(), "internal_reference_rejects_pd1_clear", passed, failed);
    adc.set_reference(Some(5.0)).expect("set_reference");
    check_true!(!adc.uses_internal_reference() && adc.get_reference() == 5.0, "external_reference", passed, failed);
    adc.set_reference(None).expect("set_reference");
    check_true!(adc.uses_internal_reference(), "internal_reference_restored", passed, failed);
    adc.power_down().expect("power_down");
    check_true!(adc.read_raw(0).expect("read") <= 4095, "read_after_power_down", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
