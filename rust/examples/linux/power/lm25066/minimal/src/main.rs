use linux_embedded_hal::I2cdev;
use periph::chips::power::Lm25066Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lm25066Minimal::new(dev, addr, 1.0, false); // Create LM25066 driver, (i2c, addr, r_sense_mohm, current_limit_high)

    loop {
        println!("VIN={:.3} V  VOUT={:.3} V", chip.voltage_in().unwrap(), chip.voltage_out().unwrap()); // Read VIN / VOUT, () → Result<f32>
        println!("IIN={:.3} A  PIN={:.2} W  T={:.1} C", chip.current().unwrap(), chip.power().unwrap(), chip.temperature().unwrap()); // Read A / W / MOSFET °C, () → Result<f32>
        sleep(Duration::from_millis(1000));
    }
}
