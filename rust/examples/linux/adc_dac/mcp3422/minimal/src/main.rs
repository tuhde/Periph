// Minimal example: read both differential inputs.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{MCP3422Minimal, MCP3422_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = MCP3422Minimal::new(dev, MCP3422_I2C_ADDRESS); // Create MCP3422 driver, (i2c, addr=0x68)
    adc.init().expect("init");                                   // Write the default configuration, () → ()

    for _ in 0..10 {
        let raw = adc.read_raw(&mut Delay, 0).expect("read_raw"); // Read a signed code, (delay, channel 0–1) → i32
        let volts = adc.read_voltage(&mut Delay, 1).expect("read_voltage"); // Read the differential voltage, (delay, channel 0–1) → f32 V
        println!("CH1 raw {} CH2 {:.6} V", raw, volts);
        println!("{:?}", adc.read_all(&mut Delay).expect("read_all")); // Read both channels, (delay) → [f32; 2]
        sleep(Duration::from_secs(1));
    }
}
