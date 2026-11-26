//! CCS811 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x5A, nWAKE tied low.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::gas::ccs811;
use periph::chips::gas::Ccs811Full;

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
    let mut chip = Ccs811Full::new(i2c, 0x5A);

    esp_println::println!("CCS811 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    let (hw_id, hw_version) = chip.hardware_version().expect("hw");
    check_true!(hw_id == 0x81, "hardware_id");
    check_true!(hw_version & 0xF0 == 0x10, "hardware_version");
    let (_, app) = chip.firmware_version().expect("fw");
    check_true!(app.major >= 1, "firmware_version");
    let st = chip.status().expect("status");
    check_true!(st & ccs811::STATUS_FW_MODE != 0, "status_app_mode");
    check_true!(st & ccs811::STATUS_ERROR == 0, "status_no_error");
    let m = chip.read(&mut delay).expect("read");
    check_true!(m.eco2_ppm >= 400 && m.eco2_ppm <= 8192, "read_eco2_range");
    check_true!(m.tvoc_ppb <= 1187, "read_tvoc_range");
    let e = chip.read_eco2(&mut delay).expect("eco2");
    check_true!(e >= 400 && e <= 8192, "read_eco2_single");
    check_true!(chip.read_tvoc(&mut delay).expect("tvoc") <= 1187, "read_tvoc_single");
    let (current, voltage) = chip.read_raw().expect("raw");
    check_true!(current <= 63, "read_raw_current");
    check_true!(voltage >= 0.0 && voltage <= 1.65, "read_raw_voltage");
    check_true!(!chip.data_ready().expect("ready"), "data_ready_cleared");
    check_true!(chip.set_environment(50.0, 25.0).is_ok(), "set_environment");
    check_true!(chip.set_thresholds(1000, 2000, 50).is_ok(), "set_thresholds");
    check_true!(chip.read_ntc().is_ok(), "read_ntc");
    let baseline = chip.get_baseline().expect("baseline");
    chip.set_baseline(baseline).expect("baseline");
    check_true!(chip.error().expect("error") == 0, "baseline_roundtrip");
    chip.enable_interrupt(ccs811::SOURCE_DATA_READY).expect("enable");
    sleep_ms!(1200);
    check_true!(chip.poll_interrupt().expect("poll") & ccs811::STATUS_DATA_READY != 0, "poll_interrupt_data_ready");
    chip.read(&mut delay).expect("read");
    chip.disable_interrupt(ccs811::SOURCE_DATA_READY).expect("disable");
    check_true!(chip.set_mode(4).is_ok(), "set_mode_4");
    sleep_ms!(600);
    check_true!(chip.read_raw().expect("raw").0 <= 63, "mode_4_raw");
    chip.set_mode(0).expect("mode");
    chip.set_mode(1).expect("mode");
    check_true!(chip.soft_reset(&mut delay).is_ok(), "soft_reset");
    check_true!(chip.status().expect("status") & ccs811::STATUS_FW_MODE != 0, "status_after_reset");
    let r = chip.read(&mut delay).expect("read");
    check_true!(r.eco2_ppm >= 400 && r.eco2_ppm <= 8192, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
