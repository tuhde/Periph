//! PCA6408A hardware test — Linux (linux-embedded-hal /dev/i2c-N).
//!
//! Wiring for the loopback checks: jumper P0–P3 to P4–P7 (Pn ↔ Pn+4).
//! Configuration: I2C_BUS (default 1), I2C_ADDR (hex, default 0x20).

use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};
use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pca6408aFull, Pca6408aMinimal};

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
    let chip = Pca6408aMinimal::new(dev, addr).expect("init PCA6408A");

    check("init_shadow", chip.shadow.get() == 0xFF);
    check("init_direction", chip.direction.get() == 0xFF);

    let _ = chip.read_port().expect("read_port");
    chip.write_port(0x55).expect("write_port");
    check("write_port_shadow", chip.shadow.get() == 0x55);
    chip.write_port(0xFF).expect("write_port");

    let mut p7 = chip.pin(7);
    p7.into_output().expect("into_output");
    p7.set_low().expect("set_low");
    check("pin7_low", chip.shadow.get() & 0x80 == 0);
    p7.set_high().expect("set_high");
    check("pin7_high", chip.shadow.get() & 0x80 == 0x80);
    check("pin7_is_set_high", p7.is_set_high().expect("is_set_high"));
    p7.into_input().expect("into_input");
    check("pin7_is_high_ok", p7.is_high().is_ok());
    drop(p7);
    drop(chip);

    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let full = Pca6408aFull::new(dev2, addr).expect("init PCA6408A full");
    full.set_direction(0xF0).expect("set_direction");
    check("config_readback", full.read_register(0x03).expect("read") == 0xF0);
    for pattern in [0x05u8, 0x0A, 0x00] {
        full.write_port(pattern).expect("write_port");
        let hi = (full.read_port().expect("read_port") >> 4) & 0x0F;
        check(&format!("loopback_{:#x}", pattern), hi == pattern);
    }
    full.configure_polarity(0xF0).expect("configure_polarity");
    check("polarity_readback", full.read_register(0x02).expect("read") == 0xF0);
    check("polarity_inverted", (full.read_port().expect("read_port") >> 4) & 0x0F == 0x0F);
    full.configure_polarity(0x00).expect("configure_polarity");
    let _ = full.poll_interrupt().expect("poll_interrupt");
    full.write_port(0x01).expect("write_port");
    check("poll_interrupt_changed", full.poll_interrupt().expect("poll_interrupt") == 0x10);
    full.write_port(0x00).expect("write_port");
    full.reset().expect("reset");
    check("reset_config", full.read_register(0x03).expect("read") == 0xFF);
    check("reset_output", full.read_register(0x01).expect("read") == 0xFF);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
