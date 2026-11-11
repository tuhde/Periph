//! PCA9536 hardware test — Linux (linux-embedded-hal /dev/i2c-N).
//!
//! Wiring for the loopback checks: jumper P0–P1 to P2–P3 (Pn ↔ Pn+2).
//! Configuration: I2C_BUS (default 1), I2C_ADDR (hex, default 0x41).

use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};
use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pca9536Full, Pca9536Minimal};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x41);

    let mut passed = 0;
    let mut failed = 0;
    let mut check = |label: &str, ok: bool| {
        if ok { println!("PASS {}", label); passed += 1; } else { println!("FAIL {}", label); failed += 1; }
    };

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca9536Minimal::new(dev, addr).expect("init PCA9536");

    check("init_shadow", chip.shadow.get() == 0xFF);
    check("init_direction", chip.direction.get() == 0xFF);

    let _ = chip.read_port().expect("read_port");
    chip.write_port(0x05).expect("write_port");
    check("write_port_shadow", chip.shadow.get() & 0x0F == 0x05);
    chip.write_port(0x0F).expect("write_port");

    let mut p3 = chip.pin(3);
    p3.into_output().expect("into_output");
    p3.set_low().expect("set_low");
    check("pin3_low", chip.shadow.get() & 0x08 == 0);
    p3.set_high().expect("set_high");
    check("pin3_high", chip.shadow.get() & 0x08 == 0x08);
    check("pin3_is_set_high", p3.is_set_high().expect("is_set_high"));
    p3.into_input().expect("into_input");
    check("pin3_is_high_ok", p3.is_high().is_ok());
    drop(p3);
    drop(chip);

    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let full = Pca9536Full::new(dev2, addr).expect("init PCA9536 full");
    full.set_direction(0x0C).expect("set_direction");
    check("config_readback", full.read_register(0x03).expect("read") & 0x0F == 0x0C);
    for pattern in [0x01u8, 0x02, 0x00] {
        full.write_port(pattern).expect("write_port");
        let hi = (full.read_port().expect("read_port") >> 2) & 0x03;
        check(&format!("loopback_{:#x}", pattern), hi == pattern);
    }
    full.configure_polarity(0x0C).expect("configure_polarity");
    check("polarity_readback", full.read_register(0x02).expect("read") & 0x0F == 0x0C);
    check("polarity_inverted", (full.read_port().expect("read_port") >> 2) & 0x03 == 0x03);
    full.configure_polarity(0x00).expect("configure_polarity");
    let _ = full.poll_changes().expect("poll_changes");
    full.write_port(0x01).expect("write_port");
    check("poll_changes_changed", full.poll_changes().expect("poll_changes") == 0x04);
    full.write_port(0x00).expect("write_port");
    full.reset().expect("reset");
    check("reset_config", full.read_register(0x03).expect("read") & 0x0F == 0x0F);
    check("reset_output", full.read_register(0x01).expect("read") & 0x0F == 0x0F);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
