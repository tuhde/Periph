//! MS5607 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x77.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::pressure::{MS5607Conversion as Conversion, MS5607Full};

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = MS5607Full::new(i2c, 0x77);

    esp_println::println!("MS5607 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    check_true!(chip.prom_crc_ok(), "prom_crc_ok");
    let (t, p) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_range");
    check_true!(p > 30000.0 && p < 120000.0, "read_pressure_range");
    let ps = chip.read_pressure(&mut delay).expect("p");
    check_true!(ps > 30000.0 && ps < 120000.0, "read_pressure_single");
    let tc = chip.read_temperature(&mut delay).expect("t");
    check_true!(tc > -40.0 && tc < 85.0, "read_temperature_single");
    let (d1, d2) = chip.read_raw(&mut delay).expect("raw");
    check_true!(d1 > 0 && d2 > 0, "read_raw");
    let (_, pc) = chip.compensate(d1, d2, true);
    check_true!((pc - p).abs() < 200.0, "compensate_matches_read");
    check_true!(chip.coefficients().iter().all(|&c| c > 0), "coefficients_nonzero");
    check_true!(chip.serial_code() < 4096, "serial_code_range");
    check_true!(chip.read_prom().is_ok(), "read_prom");
    for osr in [256u16, 512, 1024, 2048, 4096] {
        chip.set_oversampling(osr).expect("osr");
        check_true!(chip.get_oversampling() == osr, "osr_roundtrip");
        let v = chip.read_pressure(&mut delay).expect("p");
        check_true!(v > 30000.0 && v < 120000.0, "osr_read");
    }
    chip.start_conversion(Conversion::Temperature, None).expect("start");
    sleep_ms!(12);
    check_true!(chip.read_adc().map(|v| v > 0).unwrap_or(false), "non_blocking_adc");
    let alt = chip.altitude(&mut delay, 101325.0).expect("alt");
    check_true!(alt > -500.0 && alt < 6000.0, "altitude_plausible");
    chip.reset(&mut delay).expect("reset");
    let v = chip.read_pressure(&mut delay).expect("p");
    check_true!(v > 30000.0 && v < 120000.0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
