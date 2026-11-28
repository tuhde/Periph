//! ST25DV hardware-in-loop test for Linux (I2C_BUS, I2C_ADDR = user address, system at 0x57).
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::St25dvFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x53);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = St25dvFull::new(dev, Delay, addr, 0x57);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.init().is_ok(), "init");
    check_true!(chip.ic_reference() == 0x24 || chip.ic_reference() == 0x26, "ic_reference");
    let size = chip.memory_size();
    check_true!(size == 512 || size == 2048 || size == 8192, "memory_size");
    let uid = chip.uid().unwrap_or([0; 8]);
    check_true!(uid[0] == 0xE0 && uid[1] == 0x02, "uid manufacturer");
    check_true!(chip.ic_revision().is_ok(), "ic_revision");

    let addr = (size - 64) as u16;
    let mut saved = [0u8; 40];
    check_true!(chip.read(addr, &mut saved).is_ok(), "read ok");
    let mut pattern = [0u8; 40];
    for (i, b) in pattern.iter_mut().enumerate() {
        *b = (i as u8).wrapping_mul(7).wrapping_add(3);
    }
    check_true!(chip.write(addr, &pattern).is_ok(), "write ok");
    let mut back = [0u8; 40];
    check_true!(chip.read(addr, &mut back).is_ok() && back == pattern, "write/read round-trip");
    check_true!(chip.write(addr, &saved).is_ok(), "restore");
    let mut two = [0u8; 2];
    check_true!(chip.read((size - 1) as u16, &mut two).is_err(), "range rejected");

    check_true!(chip.present_password(&[0u8; 8]).unwrap_or(false), "present factory password");
    check_true!(chip.session_open().unwrap_or(false), "session open after present");
    check_true!(matches!(chip.get_area_protection(1), Ok((rf, i2c)) if rf <= 0x0F && i2c <= 3), "get_area_protection");
    check_true!(chip.get_gpo().is_ok(), "get_gpo");
    check_true!(chip.field_present().is_ok(), "field_present");
    check_true!(chip.poll_interrupt().is_ok(), "poll_interrupt");
    check_true!(chip.mailbox_status().is_ok(), "mailbox_status");
    let mut ndef = [0u8; 256];
    check_true!(chip.read_ndef(&mut ndef).is_ok(), "read_ndef");
    check_true!(chip.wait_write_complete().is_ok(), "wait_write_complete");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
