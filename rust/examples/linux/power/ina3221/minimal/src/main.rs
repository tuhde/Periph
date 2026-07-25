use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina3221Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina3221Minimal::new(dev, addr, 0.1);  // Create INA3221 driver, (i2c, addr, r_shunt=0.1 Ω)

    loop {
        for ch in 1..=3 {
            let v = chip.voltage(ch).expect("voltage");  // Read bus voltage, (channel) → f32 V
            let i = chip.current(ch).expect("current");   // Read load current, (channel) → f32 A
            let p = chip.power(ch).expect("power");      // Read power, (channel) → f32 W
            println!("ch{}: {:.3}V {:.4}A {:.4}W ", ch, v, i, p);
        }
        println!();
        sleep(Duration::from_secs(1));
    }
}