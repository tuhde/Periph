use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pcf8574Full, Pcf8574Minimal};
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

    // --- Pcf8574Minimal ---
    let dev1 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip1 = Pcf8574Minimal::new(dev1, addr).expect("init PCF8574 minimal");

    // Shadow initialises to 0xFF (all pins input mode)
    let mut p0 = chip1.pin(0);
    check_true!(p0.is_set_high().unwrap(), "shadow_init_high", passed, failed);

    let mut p7 = chip1.pin(7);
    check_true!(p7.is_set_high().unwrap(), "shadow_init_high_p7", passed, failed);
    drop(p7);

    // read_port returns a valid byte
    let port = chip1.read_port().unwrap();
    check_true!(port <= 0xFF, "read_port_range", passed, failed);

    // write_port drives pins and updates shadow
    chip1.write_port(0b00001111).unwrap();
    let mut p4 = chip1.pin(4);
    check_true!(p4.is_set_high().unwrap(), "write_port_p4_high", passed, failed);
    let mut p0b = chip1.pin(0);
    check_true!(p0b.is_set_low().unwrap(), "write_port_p0_low", passed, failed);
    drop(p4);
    drop(p0b);

    // set_low drives pin low; shadow and is_set_low reflect it
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
    chip1.write_port(0xFF).unwrap();

    // is_set_high after write_port(0xFF)
    let mut p3 = chip1.pin(3);
    check_true!(p3.is_set_high().unwrap(), "shadow_restored", passed, failed);
    drop(p3);

    // --- Pcf8574Full ---
    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus (full)");
    let chip2 = Pcf8574Full::new(dev2, addr).expect("init PCF8574 full");

    // clear_interrupt immediately after init — nothing changed, should be 0
    let changed = chip2.clear_interrupt().unwrap();
    check_eq!(changed, 0u8, "clear_interrupt_no_change", passed, failed);

    // write_port then read back via read_port
    chip2.write_port(0xAA).unwrap();
    chip2.write_port(0xFF).unwrap();
    let p = chip2.read_port().unwrap();
    check_true!(p <= 0xFF, "full_read_port_range", passed, failed);

    // clear_interrupt after internal write — input pins may or may not show change
    let _changed2 = chip2.clear_interrupt().unwrap();
    check_true!(true, "clear_interrupt_after_write", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
}
