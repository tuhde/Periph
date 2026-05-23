#![no_std]
#![no_main]

use embedded_hal::i2c::I2c as _;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::magnetometer::As5600Full;

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

    let mut delay = esp_hal::delay::Delay::new();

    println!("--- magnet status (60 s max) ---");
    for _ in 0..300 {
        let mut buf = [0u8; 1];
        i2c.write_read(TEST_ADDR, &[0x0Bu8], &mut buf).ok();
        let s = buf[0];
        i2c.write_read(TEST_ADDR, &[0x1Au8], &mut buf).ok();
        let agc = buf[0];
        let md = s & 0x08 != 0; let ml = s & 0x10 != 0; let mh = s & 0x20 != 0;
        println!("MD={} ML={} MH={} AGC={}", md as u8, ml as u8, mh as u8, agc);
        if md { break; }
        delay.delay_millis(200u32);
    }
    println!("--- end magnet status ---");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match As5600Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach AS5600 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    // --- Magnet detection ---
    check_true!(chip.is_magnet_detected().unwrap_or(false), "magnet_detected", passed, failed);

    // --- Angle readings ---
    let a_ok = chip.angle().map(|v| v >= 0.0 && v < 360.0).unwrap_or(false);
    check_true!(a_ok, "angle_range", passed, failed);

    let r_ok = chip.angle_raw().map(|v| v <= 4095).unwrap_or(false);
    check_true!(r_ok, "angle_raw_range", passed, failed);

    let ra_ok = chip.raw_angle().map(|v| v <= 4095).unwrap_or(false);
    check_true!(ra_ok, "raw_angle_range", passed, failed);

    let rad_ok = chip.raw_angle_degrees().map(|v| v >= 0.0 && v < 360.0).unwrap_or(false);
    check_true!(rad_ok, "raw_angle_degrees_range", passed, failed);

    // --- Diagnostics ---
    check_true!(chip.agc().is_ok(), "agc_ok", passed, failed);
    check_true!(chip.magnitude().is_ok(), "magnitude_ok", passed, failed);

    // --- Status ---
    check_true!(chip.status_byte().is_ok(), "status_byte_ok", passed, failed);

    // --- Position configuration (volatile) ---
    chip.set_zero_position(100).ok();
    let zp_ok = chip.zero_position().map(|v| v == 100).unwrap_or(false);
    check_true!(zp_ok, "zero_position_after_set", passed, failed);

    chip.set_max_position(2000).ok();
    let mp_ok = chip.max_position().map(|v| v == 2000).unwrap_or(false);
    check_true!(mp_ok, "max_position_after_set", passed, failed);

    chip.set_max_angle(2048).ok();
    let ma_ok = chip.max_angle().map(|v| v == 2048).unwrap_or(false);
    check_true!(ma_ok, "max_angle_after_set", passed, failed);

    // --- Configure ---
    chip.configure(0, 0, 0, 0, 0, 0, false).ok();
    check_true!(chip.is_magnet_detected().unwrap_or(false), "configure_accepted", passed, failed);

    // --- Burn count ---
    let bc_ok = chip.burn_count().map(|v| v <= 3).unwrap_or(false);
    check_true!(bc_ok, "burn_count_range", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
