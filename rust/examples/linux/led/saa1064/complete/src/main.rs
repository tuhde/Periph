//! Complete example for the SAA1064 — every SAA1064Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::SAA1064Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SAA1064 driver, (i2c, addr=0x38) → SAA1064
    let mut chip = SAA1064Full::new(dev, addr).expect("new");

    // Show four digits, (digits [Option<u8>; 4], 0–9 or None) → Result<bool>; default mapping a=bit0 … g=bit6, dp=bit7
    chip.show_digits([Some(1), Some(2), Some(3), Some(4)]).expect("show_digits");
    sleep_ms!(1000);
    // Show a number, (value 0–9999) → Result<bool>; leading zeros are blanked
    chip.show_number(42).expect("show_number");
    // Write raw segment bytes, (patterns &[u8] 1–4, first_digit 1–4) → Result<bool>; digits 3 and 4 show 8
    chip.write_raw(&[0x7F, 0x7F], 3).expect("write_raw");
    sleep_ms!(1000);
    // Show one digit, (position 1–4, value Option<u8>, dp) → Result<bool>; digit 2 = 7 with decimal point
    chip.show_digit(2, Some(7), true).expect("show_digit");
    // Switch a decimal point, (position 1–4, on) → Result<bool>; keeps the cached segments
    chip.set_decimal_point(2, false).expect("set_decimal_point");
    // Remap segment bits, (map [u8; 8] for a–g, dp → bit 0–7) → bool
    chip.set_segment_map([0, 1, 2, 3, 4, 5, 6, 7]);
    // Set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → Result<bool>; C4–C6
    chip.set_current(21).expect("set_current");
    // Select static/dynamic mode, (dynamic) → Result<()>; static shows only digits 1 and 2
    chip.set_mode(false).expect("set_mode");
    sleep_ms!(1000);
    chip.set_mode(true).expect("set_mode");
    // Blank digit pairs, (digits_13, digits_24) → Result<()>; digits 1+3 off
    chip.blank(true, false).expect("blank");
    sleep_ms!(1000);
    chip.blank(false, false).expect("blank");
    // Segment test, (on) → Result<()>; lights every segment
    chip.segment_test(true).expect("segment_test");
    sleep_ms!(1000);
    chip.segment_test(false).expect("segment_test");
    // Read the status byte, () → Result<u8>
    println!("status 0x{:02X}", chip.read_status().expect("read_status"));
    // Read the PR flag, () → Result<bool>; set after power-up, clears when read
    println!("power reset {}", chip.power_reset_flag().expect("power_reset_flag"));
    // Write the raw control byte, (value 0–127) → Result<()>; dynamic mode, unblanked, 12 mA
    chip.write_control(0x47).expect("write_control");
    // Blank all digits, () → Result<()>
    chip.clear().expect("clear");
}
