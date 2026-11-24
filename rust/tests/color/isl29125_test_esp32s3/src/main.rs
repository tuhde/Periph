#![no_std]
#![no_main]

//! ISL29125 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x44.

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::time::Rate;
use periph::chips::color::{Isl29125Channel, Isl29125Full, Isl29125Mode, ISL29125_I2C_ADDRESS, ISL29125_SOURCE_THRESHOLD, ISL29125_STATUS_THRESHOLD};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = Isl29125Full::new(i2c, ISL29125_I2C_ADDRESS);
    chip.init().expect("init");

    esp_println::println!("ISL29125 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.device_id().ok() == Some(0x7D), "device_id");
    let c = chip.read(&mut delay).expect("read");
    check_true!((c.red as u32 + c.green as u32 + c.blue as u32) < 3 * 65536, "read_range");
    check_true!(chip.set_range(375).is_ok() && chip.set_range(10_000).is_ok(), "set_range");
    check_true!(chip.set_resolution(12).is_ok() && chip.set_resolution(16).is_ok(), "set_resolution");
    check_true!(chip.set_range(100).is_err() && chip.set_resolution(8).is_err(), "invalid_config");
    check_true!(chip.set_ir_compensation(true, 0x3F).is_ok(), "ir_compensation");
    let _ = chip.set_sync(false);
    check_true!(chip.read_rgb().is_ok(), "read_rgb");
    check_true!(chip.read_green().is_ok() && chip.read_red().is_ok() && chip.read_blue().is_ok(), "read_single");
    check_true!(chip.set_mode(Isl29125Mode::Green).is_ok() && chip.read_green().is_ok(), "green_mode_reads");
    let _ = chip.set_mode(Isl29125Mode::Rgb);
    check_true!(chip.set_thresholds(100, 60_000).is_ok() && chip.get_thresholds().ok() == Some((100, 60_000)), "thresholds_roundtrip");
    check_true!(chip.set_persistence(2).is_ok() && chip.set_interrupt_channel(Isl29125Channel::Green).is_ok(), "interrupt_config");
    let _ = chip.enable_interrupt(ISL29125_SOURCE_THRESHOLD);
    let _ = chip.disable_interrupt(ISL29125_SOURCE_THRESHOLD);
    let _ = chip.status();
    check_true!(chip.poll_interrupt().map(|s| s & ISL29125_STATUS_THRESHOLD == 0).unwrap_or(false), "status_clean");
    let _ = chip.set_mode(Isl29125Mode::PowerDown);
    let _ = chip.soft_reset(&mut delay);
    let _ = chip.set_mode(Isl29125Mode::Rgb);
    check_true!(chip.device_id().ok() == Some(0x7D), "soft_reset_id");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
