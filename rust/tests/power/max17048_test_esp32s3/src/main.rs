#![no_std]
#![no_main]

use embedded_hal::delay::DelayNs;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Max17048Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x36;

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

    let mut chip = match Max17048Full::new(i2c, TEST_ADDR, 1) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach MAX17048 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.voltage().map(|v| v >= 2.5 && v <= 4.6).unwrap_or(false), "voltage_range", passed, failed);
    check_true!(chip.percent().map(|v| v >= 0.0 && v <= 100.0).unwrap_or(false), "percent_range", passed, failed);
    check_true!(chip.version().map(|v| v & 0xFFF0 == 0x0010).unwrap_or(false), "version", passed, failed);
    check_true!(chip.raw_voltage().map(|r| ((r >> 4) as f32 * 1.25e-3) > 2.5).unwrap_or(false), "raw_voltage", passed, failed);
    check_true!(chip.charge_rate().map(|v| v.abs() < 1000.0).unwrap_or(false), "charge_rate", passed, failed);
    check_true!(chip.set_rcomp(0x97).is_ok() && chip.get_rcomp().map(|v| v == 0x97).unwrap_or(false), "rcomp", passed, failed);
    check_true!(chip.compensate_temperature(20.0).is_ok() && chip.get_rcomp().map(|v| v == 0x97).unwrap_or(false), "compensate_20c", passed, failed);
    check_true!(chip.set_alert_soc(10).is_ok() && chip.get_alert_soc().map(|v| v == 10).unwrap_or(false), "alert_soc", passed, failed);
    let _ = chip.set_alert_soc(4);
    check_true!(chip.set_voltage_alert(3.0, 4.2).is_ok() && chip.get_voltage_alert().map(|(lo, hi)| (lo - 3.0).abs() < 0.001 && (hi - 4.2).abs() < 0.001).unwrap_or(false), "voltage_alert", passed, failed);
    let _ = chip.set_voltage_alert(0.0, 5.1);
    check_true!(chip.set_reset_voltage(2.8).is_ok() && chip.get_reset_voltage().map(|v| (v - 2.8).abs() < 0.001).unwrap_or(false), "reset_voltage", passed, failed);
    let _ = chip.set_reset_voltage(3.0);
    check_true!(chip.set_hibernate_thresholds(26.624, 0.06).is_ok() && chip.get_hibernate_thresholds().map(|(h, a)| (h - 26.624).abs() < 0.001 && (a - 0.06).abs() < 0.001).unwrap_or(false), "hibernate_thresholds", passed, failed);
    check_true!(chip.clear_status(0x3F00).is_ok() && chip.read_status().map(|v| v & 0x3F00 == 0).unwrap_or(false), "status_cleared", passed, failed);
    check_true!(chip.poll_interrupt().map(|v| v == 0).unwrap_or(false), "poll_interrupt", passed, failed);
    check_true!(chip.read_config().and_then(|c| chip.write_config(c)).is_ok() && chip.read_config().map(|v| v >> 8 == 0x97).unwrap_or(false), "config", passed, failed);
    check_true!(chip.enable_sleep(true).is_ok() && chip.sleep().is_ok(), "sleep", passed, failed);
    delay.delay_ms(10);
    check_true!(chip.wake().is_ok() && chip.enable_sleep(false).is_ok(), "wake", passed, failed);
    check_true!(chip.voltage().map(|v| v > 0.0).unwrap_or(false), "voltage_after_wake", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
