//! Demo for the SAA1064 — four-digit counter, decimal point sweep, current steps, static mode and segment test.

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

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four positions,
    // the segment current stepped through 3/9/15/21 mA, a switch to static mode (only digits
    // 1 and 2 stay lit), a 2 s segment test and the status byte (the PR flag is set after
    // power-up and clears on the first read).
    let mut n: u16 = 0;
    while n < 10000 {
        // Show the counter, (value 0–9999) → Result<bool>
        chip.show_number(n).expect("show_number");
        sleep_ms!(10);
        n += 37;
    }

    // Show four digits, (digits) → Result<bool>
    chip.show_digits([Some(1), Some(2), Some(3), Some(4)]).expect("show_digits");
    for pos in 1..=4u8 {
        // Light a decimal point, (position, on) → Result<bool>
        chip.set_decimal_point(pos, true).expect("dp on");
        sleep_ms!(400);
        chip.set_decimal_point(pos, false).expect("dp off");
    }

    for ma in [3u8, 9, 15, 21] {
        // Step the segment current, (ma) → Result<bool>
        chip.set_current(ma).expect("set_current");
        chip.show_number(8888).expect("show_number");
        sleep_ms!(1000);
    }
    chip.set_current(12).expect("set_current");

    // Static mode, (dynamic) → Result<()>
    chip.set_mode(false).expect("static");
    sleep_ms!(2000);
    chip.set_mode(true).expect("dynamic");

    // Segment test, (on) → Result<()>
    chip.segment_test(true).expect("test on");
    sleep_ms!(2000);
    chip.segment_test(false).expect("test off");

    // Read the status byte, () → Result<u8>
    println!("status 0x{:02X}", chip.read_status().expect("read_status"));
    // Read the PR flag, () → Result<bool>
    println!("power reset {}", chip.power_reset_flag().expect("power_reset_flag"));
    // Blank all digits, () → Result<()>
    chip.clear().expect("clear");
}
