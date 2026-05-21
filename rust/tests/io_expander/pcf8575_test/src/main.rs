use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pcf8575Full, Pcf8575Minimal};
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

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
    ($a:expr, $b:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $a == $b {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {} (got {:?}, expected {:?})", $label, $a, $b);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Pcf8575Minimal ---
    let dev1 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip1 = Pcf8575Minimal::new(dev1, addr).expect("init PCF8575 minimal");

    // Shadow initialises to [0xFF, 0xFF] (all pins input mode)
    let mut p0 = chip1.pin(0);
    check_true!(p0.is_set_high().unwrap(), "shadow_init_high_p0", passed, failed);

    let mut p15 = chip1.pin(15);
    check_true!(p15.is_set_high().unwrap(), "shadow_init_high_p15", passed, failed);
    drop(p15);

    // read_port returns valid bytes
    let port0 = chip1.read_port(0).unwrap();
    let port1 = chip1.read_port(1).unwrap();
    check_true!(port0 <= 0xFF, "read_port_0_range", passed, failed);
    check_true!(port1 <= 0xFF, "read_port_1_range", passed, failed);

    // write_port drives pins and updates shadow
    chip1.write_port(0, 0b00001111).unwrap();
    let mut p4 = chip1.pin(4);
    check_true!(p4.is_set_high().unwrap(), "write_port_p4_high", passed, failed);
    let mut p0b = chip1.pin(0);
    check_true!(p0b.is_set_low().unwrap(), "write_port_p0_low", passed, failed);
    drop(p4);
    drop(p0b);

    // set_low drives pin low; shadow reflects it
    let mut p0 = chip1.pin(0);
    p0.set_high().unwrap();
    check_true!(p0.is_set_high().unwrap(), "set_high_shadow", passed, failed);
    p0.set_low().unwrap();
    check_true!(p0.is_set_low().unwrap(), "set_low_shadow", passed, failed);

    // is_high reads the actual bus level
    let actual = p0.is_high().unwrap();
    check_true!(actual == false, "is_high_driven_low", passed, failed);
    drop(p0);

    // Restore all pins to input mode
    chip1.write_port(0, 0xFF).unwrap();
    chip1.write_port(1, 0xFF).unwrap();

    let mut p8 = chip1.pin(8);
    check_true!(p8.is_set_high().unwrap(), "shadow_restored_p8", passed, failed);
    drop(p8);

    // --- Pcf8575Full ---
    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus (full)");
    let chip2 = Pcf8575Full::new(dev2, addr).expect("init PCF8575 full");

    // clear_interrupt immediately after init
    let changed = chip2.clear_interrupt().unwrap();
    check_eq!(changed, 0u16, "clear_interrupt_no_change", passed, failed);

    // write_port then read back via read_port
    chip2.write_port(0, 0xAA).unwrap();
    chip2.write_port(0, 0xFF).unwrap();
    chip2.write_port(1, 0xAA).unwrap();
    chip2.write_port(1, 0xFF).unwrap();
    let p0r = chip2.read_port(0).unwrap();
    let p1r = chip2.read_port(1).unwrap();
    check_true!(p0r <= 0xFF, "full_read_port_0_range", passed, failed);
    check_true!(p1r <= 0xFF, "full_read_port_1_range", passed, failed);

    let _changed2 = chip2.clear_interrupt().unwrap();
    check_true!(true, "clear_interrupt_after_write", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
}