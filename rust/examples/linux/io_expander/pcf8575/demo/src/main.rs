use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pcf8575Full;
use embedded_hal::digital::OutputPin;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pcf8575Full::new(dev, addr).expect("init PCF8575");  // Create PCF8575 full driver, (i2c, addr=0x20) → Result

    chip.write_port(0, 0xFF).expect("write_port 0");                // Write Port 0, (port=0, mask) → Result<(), E>
    chip.write_port(1, 0xFF).expect("write_port 1");                 // Write Port 1, (port=1, mask) → Result<(), E>

    println!("Running — buttons on P10–P17 mirror to LEDs on P00–P07");
    loop {
        let port0 = chip.read_port(0).expect("read_port port0");     // Read Port 0, (port=0) → Result<u8, E>
        let port1 = chip.read_port(1).expect("read_port port1");     // Read Port 1, (port=1) → Result<u8, E>

        let buttons = port1 & 0xFF;                                   // P10–P17 (pressed = 0)
        let led_bits = !buttons;                                     // invert: pressed → LED on (0)
        chip.write_port(0, led_bits).expect("write_port 0");        // Write Port 0, (port=0, mask) → Result<(), E>

        println!(
            "P0=0x{:02X}  P1=0x{:02X}  buttons={}  LEDs={}",
            port0, port1,
            String::from_iter((0..8).map(|i| if (buttons >> (7 - i)) & 1 != 0 { '.' } else { 'X' })),
            String::from_iter((0..8).map(|i| if (led_bits >> (7 - i)) & 1 != 0 { ' ' } else { '*' }))
        );
        sleep(Duration::from_millis(200));
    }
}