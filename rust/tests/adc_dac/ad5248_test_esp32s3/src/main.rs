#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::Ad5248Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x2C;

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

    let mut chip = Ad5248Full::new(i2c, TEST_ADDR, 10000.0, 160.0);
    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Wiper write / readback ---
    chip.set_wiper(0, 100).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 100).unwrap_or(false), "set/get wiper ch0", passed, failed);
    chip.set_wiper(1, 200).ok();
    check_true!(chip.get_wiper(1).map(|v| v == 200).unwrap_or(false), "set/get wiper ch1", passed, failed);
    check_true!(chip.get_wiper(0).map(|v| v == 100).unwrap_or(false), "ch0 unchanged by ch1 write", passed, failed);

    // --- Resistance ---
    chip.set_resistance(0, 5320.0).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 128).unwrap_or(false), "set_resistance midscale code", passed, failed);
    check_true!(chip.get_resistance(0).map(|r| (r - 5320.0).abs() < 1.0).unwrap_or(false), "get_resistance ~5320", passed, failed);

    // --- Ratio / increment / both ---
    chip.set_both(10, 20).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 10).unwrap_or(false), "set_both ch0", passed, failed);
    check_true!(chip.get_wiper(1).map(|v| v == 20).unwrap_or(false), "set_both ch1", passed, failed);
    chip.set_ratio(0, 0.5).ok();
    check_true!(chip.get_ratio(0).map(|r| (r - 0.5).abs() < 0.01).unwrap_or(false), "get_ratio ~0.5", passed, failed);
    chip.set_wiper(0, 250).ok();
    check_true!(chip.increment(0, 10).map(|v| v == 255).unwrap_or(false), "increment clamps", passed, failed);

    // --- Shutdown keeps RDAC ---
    chip.set_wiper(1, 77).ok();
    chip.shutdown(1).ok();
    check_true!(chip.is_shutdown(1), "is_shutdown", passed, failed);
    chip.resume(1).ok();
    check_true!(chip.get_wiper(1).map(|v| v == 77).unwrap_or(false), "RDAC kept across shutdown", passed, failed);

    // --- Reset ---
    chip.reset().ok();
    check_true!(chip.get_wiper(0).map(|v| v == 0x80).unwrap_or(false), "reset ch0", passed, failed);
    check_true!(chip.get_wiper(1).map(|v| v == 0x80).unwrap_or(false), "reset ch1", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
