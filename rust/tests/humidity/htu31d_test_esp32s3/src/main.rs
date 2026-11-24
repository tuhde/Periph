//! HTU31D hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x40.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::humidity::HTU31DFull;

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
    let mut chip = HTU31DFull::new(i2c, 0x40);

    esp_println::println!("HTU31D test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "read_temperature_range");
    check_true!(rh >= 0.0 && rh <= 100.0, "read_humidity_range");
    let h = chip.read_humidity(&mut delay).expect("rh");
    check_true!(h >= 0.0 && h <= 100.0, "read_humidity_single");
    let tc = chip.read_temperature(&mut delay).expect("t");
    check_true!(tc > -40.0 && tc < 125.0, "read_temperature_single");
    let (tr, rr) = chip.read_raw(&mut delay).expect("raw");
    check_true!(tr as u32 <= 65535 && rr as u32 <= 65535, "read_raw_range");
    check_true!(chip.dew_point(&mut delay).expect("dew") <= tc + 1.0, "dew_point_below_temperature");
    check_true!(chip.serial_number().expect("serial") <= 0xFFFFFF, "serial_number_24bit");
    check_true!(chip.diagnostic().expect("diagnostic") & periph::chips::humidity::htu31d::DIAG_NVM_ERR == 0, "diagnostic_no_nvm_error");
    for osr in 0..4u8 {
        chip.set_resolution(osr, osr).expect("resolution");
        check_true!(chip.get_resolution() == (osr, osr), "resolution_roundtrip");
        let (_, r) = chip.read(&mut delay).expect("read");
        check_true!(r >= 0.0 && r <= 100.0, "read_at_osr");
    }
    chip.set_resolution(3, 3).expect("resolution");
    check_true!((chip.conversion_time() - 0.02032).abs() < 1e-5, "conversion_time_osr33");
    chip.start_conversion().expect("start");
    sleep_ms!(30);
    let (_, r) = chip.read_result().expect("result");
    check_true!(r >= 0.0 && r <= 100.0, "start_conversion_read_result");
    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("is_heater_on"), "heater_on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("is_heater_on"), "heater_off");
    chip.software_reset(&mut delay).expect("reset");
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
