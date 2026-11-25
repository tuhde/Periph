//! PCA6416A hardware test — Linux (linux-embedded-hal /dev/i2c-N).
//!
//! Wiring for the loopback checks: jumper port 0 to port 1 (P0_n ↔ P1_n).
//! Configuration: I2C_BUS (default 1), I2C_ADDR (hex, default 0x20).

use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};
use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pca6416aFull, Pca6416aMinimal};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let mut passed = 0;
    let mut failed = 0;
    let mut check = |label: &str, ok: bool| {
        if ok { println!("PASS {}", label); passed += 1; } else { println!("FAIL {}", label); failed += 1; }
    };

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca6416aMinimal::new(dev, addr).expect("init PCA6416A");

    check("init_shadow", chip.shadow.get() == 0xFFFF);
    check("init_direction", chip.direction.get() == 0xFFFF);

    let _ = chip.read_port(0).expect("read_port");
    let _ = chip.read_port(1).expect("read_port");
    chip.write_port(0, 0x55).expect("write_port");
    chip.write_port(1, 0xAA).expect("write_port");
    check("write_port_shadow", chip.shadow.get() == 0xAA55);
    chip.write_port(0, 0xFF).expect("write_port");
    chip.write_port(1, 0xFF).expect("write_port");

    let mut p15 = chip.pin(15);
    p15.into_output().expect("into_output");
    p15.set_low().expect("set_low");
    check("pin15_low", chip.shadow.get() & 0x8000 == 0);
    p15.set_high().expect("set_high");
    check("pin15_high", chip.shadow.get() & 0x8000 == 0x8000);
    check("pin15_is_set_high", p15.is_set_high().expect("is_set_high"));
    p15.into_input().expect("into_input");
    check("pin15_is_high_ok", p15.is_high().is_ok());
    drop(p15);
    drop(chip);

    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let full = Pca6416aFull::new(dev2, addr).expect("init PCA6416A full");
    full.set_direction(0xFF00).expect("set_direction");
    check("config0_readback", full.read_register(0x06).expect("read") == 0x00);
    check("config1_readback", full.read_register(0x07).expect("read") == 0xFF);
    for pattern in [0x5Au8, 0xA5, 0x00] {
        full.write_port(0, pattern).expect("write_port");
        let hi = full.read_port(1).expect("read_port");
        check(&format!("loopback_{:#x}", pattern), hi == pattern);
    }
    full.write_all(0x00FF).expect("write_all");
    check("read_all_pair", full.read_all().expect("read_all") == 0xFFFF);
    full.write_all(0x0000).expect("write_all");
    full.configure_polarity(0xFF00).expect("configure_polarity");
    check("polarity1_readback", full.read_register(0x05).expect("read") == 0xFF);
    check("polarity_inverted", full.read_port(1).expect("read_port") == 0xFF);
    full.configure_polarity(0x0000).expect("configure_polarity");
    let _ = full.poll_interrupt().expect("poll_interrupt");
    full.write_port(0, 0x01).expect("write_port");
    check("poll_interrupt_changed", full.poll_interrupt().expect("poll_interrupt") == 0x0100);
    full.write_port(0, 0x00).expect("write_port");
    full.reset().expect("reset");
    check("reset_config0", full.read_register(0x06).expect("read") == 0xFF);
    check("reset_config1", full.read_register(0x07).expect("read") == 0xFF);
    check("reset_output0", full.read_register(0x02).expect("read") == 0xFF);
    check("reset_output1", full.read_register(0x03).expect("read") == 0xFF);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
