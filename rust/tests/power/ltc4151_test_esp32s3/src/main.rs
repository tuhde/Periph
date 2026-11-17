#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ltc4151Full};
use periph::chips::power::CH_VIN;
use esp_hal::delay::Delay;

esp_app_desc!();

const TEST_ADDR: u8 = 0x6A;

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

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match Ltc4151Full::new(i2c, TEST_ADDR, 0.02) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach LTC4151 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let mut delay = Delay::new();

    check_true!(chip.voltage().map(|v| v >= 7.0 && v <= 80.0).unwrap_or(false), "voltage_range", passed, failed);
    check_true!(chip.current().map(|v| v >= 0.0 && v <= 4.096).unwrap_or(false), "current_range", passed, failed);
    check_true!(chip.power().map(|v| v >= 0.0 && v <= 330.0).unwrap_or(false), "power_range", passed, failed);
    check_true!(chip.sense_voltage().map(|v| v >= 0.0 && v <= 0.08192).unwrap_or(false), "sense_voltage_range", passed, failed);
    check_true!(chip.adin_voltage().map(|v| v >= 0.0 && v <= 2.048).unwrap_or(false), "adin_range", passed, failed);
    check_true!(chip.read_all().map(|r| r.vin >= 7.0 && r.vin <= 80.0).unwrap_or(false), "read_all_vin", passed, failed);
    check_true!(chip.read_raw(CH_VIN).map(|c| c > 0 && c < 4096).unwrap_or(false), "read_raw_vin", passed, failed);
    check_true!(chip.read_control().map(|c| c == 0x0C).unwrap_or(false), "control_default", passed, failed);
    check_true!(chip.is_snapshot_mode().map(|m| !m).unwrap_or(false), "not_snapshot_mode", passed, failed);
    check_true!(chip.snapshot(CH_VIN, &mut delay).map(|v| v >= 7.0 && v <= 80.0).unwrap_or(false), "snapshot_vin", passed, failed);
    check_true!(chip.is_snapshot_mode().map(|m| !m).unwrap_or(false), "back_in_continuous", passed, failed);
    chip.set_stuck_bus_timer(false).ok();
    check_true!(chip.read_control().map(|c| c == 0x08).unwrap_or(false), "stuck_bus_timer_off", passed, failed);
    chip.set_stuck_bus_timer(true).ok();
    check_true!(chip.read_control().map(|c| c == 0x0C).unwrap_or(false), "stuck_bus_timer_on", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
