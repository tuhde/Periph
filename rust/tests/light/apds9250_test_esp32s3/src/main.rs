#![no_std]
#![no_main]

//! APDS-9250 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x52.

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::time::Rate;
use periph::chips::light::{Apds9250Channel, Apds9250Full, APDS9250_I2C_ADDRESS, APDS9250_SOURCE_VARIATION, APDS9250_STATUS_INTERRUPT};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = Apds9250Full::new(i2c, APDS9250_I2C_ADDRESS);
    chip.init(&mut delay).expect("init");

    esp_println::println!("APDS-9250 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.part_id().map(|p| p.0 == 0xB).unwrap_or(false), "part_id");
    check_true!(chip.get_resolution().ok() == Some(18) && chip.get_measurement_rate().ok() == Some(100) && chip.get_gain().ok() == Some(3), "defaults");
    let c = chip.read(&mut delay).expect("read");
    check_true!(c.red <= 0xFFFFF && c.green <= 0xFFFFF && c.blue <= 0xFFFFF && c.ir <= 0xFFFFF, "read_range");
    check_true!(chip.set_gain(18).is_ok() && chip.get_gain().ok() == Some(18), "gain_18");
    let _ = chip.set_gain(3);
    check_true!(chip.set_resolution(16).is_ok() && chip.get_resolution().ok() == Some(16), "resolution_16");
    check_true!(chip.integration_time().map(|t| (t - 0.025).abs() < 1e-4).unwrap_or(false), "integration_time");
    let _ = chip.set_resolution(18);
    check_true!(chip.set_measurement_rate(200).is_ok() && chip.get_measurement_rate().ok() == Some(200), "rate_200");
    let _ = chip.set_measurement_rate(100);
    check_true!(chip.read_channels().map(|c| c.green <= 0xFFFFF).unwrap_or(false), "read_channels_range");
    check_true!(chip.read_green().map(|v| v <= 0xFFFFF).unwrap_or(false), "read_single_green");
    let _ = chip.set_mode(false);
    check_true!(chip.read_ir().map(|v| v <= 0xFFFFF).unwrap_or(false), "als_mode_reads");
    let _ = chip.set_mode(true);
    check_true!(chip.set_thresholds(100, 200_000).is_ok() && chip.get_thresholds().ok() == Some((100, 200_000)), "thresholds_roundtrip");
    check_true!(chip.set_persistence(3).is_ok() && chip.set_variance(64).is_ok() && chip.set_interrupt_channel(Apds9250Channel::Green).is_ok(), "interrupt_config");
    let _ = chip.enable_interrupt(APDS9250_SOURCE_VARIATION);
    let _ = chip.disable_interrupt();
    let _ = chip.status();
    check_true!(chip.poll_interrupt().map(|s| s & APDS9250_STATUS_INTERRUPT == 0).unwrap_or(false), "status_clean");
    let _ = chip.disable();
    let _ = chip.enable();
    let _ = chip.soft_reset(&mut delay);
    check_true!(chip.get_gain().ok() == Some(3) && chip.get_resolution().ok() == Some(18), "soft_reset_defaults");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
