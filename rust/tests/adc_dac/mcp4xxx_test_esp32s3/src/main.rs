#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::{Mcp4xxxFull, Mcp4xxxPart};

esp_app_desc!();

/// Part under test; its default address is used.
const TEST_PART: Mcp4xxxPart = Mcp4xxxPart::Mcp4532;

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

    let mut chip = Mcp4xxxFull::new(i2c, TEST_PART.default_address(), TEST_PART, 10000.0, 75.0);
    let mut delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;
    let max = chip.steps() as i32 - 1;

    chip.init().ok();

    // --- Wiper write / readback ---
    chip.set_wiper(100 % (max + 1), 0).ok();
    check_true!(chip.get_wiper(0).map(|v| v as i32 == 100 % (max + 1)).unwrap_or(false), "set/get wiper", passed, failed);
    chip.set_wiper(9999, 0).ok();
    check_true!(chip.get_wiper(0).map(|v| v as i32 == max).unwrap_or(false), "clamps high", passed, failed);
    chip.set_wiper(-5, 0).ok();
    check_true!(chip.get_wiper(0).map(|v| v == 0).unwrap_or(false), "clamps low", passed, failed);
    if chip.channels() == 2 {
        chip.set_both(10, 20).ok();
        check_true!(chip.get_wiper(0).map(|v| v == 10).unwrap_or(false), "set_both ch0", passed, failed);
        check_true!(chip.get_wiper(1).map(|v| v == 20).unwrap_or(false), "set_both ch1", passed, failed);
    }

    // --- Resistance and ratio ---
    chip.set_resistance(5075.0, 0).ok();
    check_true!(chip.get_wiper(0).map(|v| (v as i32 - max / 2).abs() <= 1).unwrap_or(false), "set_resistance ~mid", passed, failed);
    check_true!(chip.get_resistance(0).map(|r| (r - 5075.0).abs() < 100.0).unwrap_or(false), "get_resistance ~5075", passed, failed);
    chip.set_ratio(0.5, 0).ok();
    check_true!(chip.get_ratio(0).map(|r| (r - 0.5).abs() < 0.01).unwrap_or(false), "get_ratio ~0.5", passed, failed);
    if chip.is_potentiometer() {
        chip.set_wiper(0, 0).ok();
        check_true!(chip.get_resistance_wa(0).map(|r| (r - 10075.0).abs() < 100.0).unwrap_or(false), "R_WA at zero scale ~RAB", passed, failed);
    }

    // --- Increment / decrement (continuous) ---
    chip.set_wiper(max - 3, 0).ok();
    check_true!(chip.increment(10, 0).map(|v| v as i32 == max).unwrap_or(false), "increment saturates", passed, failed);
    chip.set_wiper(3, 0).ok();
    check_true!(chip.decrement(10, 0).map(|v| v == 0).unwrap_or(false), "decrement saturates", passed, failed);
    check_true!(chip.increment(1, 0).map(|v| v == 1).unwrap_or(false), "increment 1", passed, failed);

    // --- Shutdown / terminals ---
    chip.set_wiper(max / 4, 0).ok();
    chip.shutdown(0).ok();
    check_true!(chip.is_shutdown(0).unwrap_or(false), "is_shutdown", passed, failed);
    chip.resume(0).ok();
    check_true!(!chip.is_shutdown(0).unwrap_or(true), "not shutdown", passed, failed);
    check_true!(chip.get_wiper(0).map(|v| v as i32 == max / 4).unwrap_or(false), "wiper kept across shutdown", passed, failed);
    chip.set_terminals(true, false, true, 0).ok();
    check_true!(chip.get_terminals(0).map(|t| t == (true, false, true)).unwrap_or(false), "terminals", passed, failed);
    chip.set_terminals(true, true, true, 0).ok();
    check_true!(chip.get_tcon().map(|t| t & 0xFF == 0xFF).unwrap_or(false), "tcon restored", passed, failed);

    // --- Non-volatile parts ---
    if chip.is_nonvolatile() {
        check_true!(chip.read_status().map(|s| !s.eeprom_busy).unwrap_or(false), "status no busy", passed, failed);
        chip.set_wiper(max / 2, 0).ok();
        check_true!(chip.store_wiper(&mut delay, 0).map(|v| v as i32 == max / 2).unwrap_or(false), "store_wiper", passed, failed);
        check_true!(chip.get_nv_wiper(0).map(|v| v as i32 == max / 2).unwrap_or(false), "get_nv_wiper", passed, failed);
        chip.write_data(&mut delay, 0, 0x155).ok();
        check_true!(chip.read_data(0).map(|v| v == 0x155).unwrap_or(false), "data roundtrip", passed, failed);
    }

    // --- Reset ---
    chip.set_wiper(1, 0).ok();
    chip.reset().ok();
    check_true!(chip.get_wiper(0).map(|v| v as i32 == max / 2).unwrap_or(false), "reset wiper", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
