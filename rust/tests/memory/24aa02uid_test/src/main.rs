use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::Eeprom24Aa02UidFull;

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

macro_rules! check_eq {
    ($label:expr, $got:expr, $expected:expr, $passed:expr, $failed:expr) => {
        if $got == $expected {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}: got {:?}, expected {:?}", $label, $got, $expected);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Eeprom24Aa02UidFull::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let uid = chip.read_uid().expect("read_uid");
    check_true!(uid.len() == 4, "read_uid_length", passed, failed);
    check_eq!("read_manufacturer_code", chip.read_manufacturer_code().unwrap(), 0x29u8, passed, failed);
    check_eq!("read_device_code",       chip.read_device_code().unwrap(),       0x41u8, passed, failed);

    chip.write_byte(0x10, 0x5A, &mut delay).expect("write_byte");
    check_eq!("write_byte_read_byte_round_trip", chip.read_byte(0x10).unwrap(), 0x5Au8, passed, failed);

    chip.write_page(0x40, &[0x11, 0x22, 0x33, 0x44], &mut delay).expect("write_page");
    let mut page_read = [0u8; 4];
    chip.read(0x40, &mut page_read).expect("read");
    check_eq!("write_page_read_back", page_read, [0x11u8, 0x22, 0x33, 0x44], passed, failed);

    chip.write(0x06, &[0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF], &mut delay).expect("write");
    let mut cross_read = [0u8; 6];
    chip.read(0x06, &mut cross_read).expect("read");
    check_eq!("cross_page_write_read_back", cross_read, [0xAAu8, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF], passed, failed);

    let mut range_read = [0u8; 16];
    chip.read(0x50, &mut range_read).expect("read");
    check_true!(range_read.len() == 16, "sequential_read_length", passed, failed);

    let uid2 = chip.read_uid().expect("read_uid");
    check_eq!("uid_unchanged_after_writes", uid2, uid, passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
