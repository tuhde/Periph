use linux_embedded_hal::I2cdev;
use periph::chips::power::Max17048Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x36);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Max17048Minimal::new(dev, addr, 1).expect("init MAX17048");     // Create MAX17048 driver, (i2c, addr, cells)

    loop {
        let v = chip.voltage().expect("voltage");   // Read cell voltage, () → Result<f32>
        let p = chip.percent().expect("percent");   // Read state of charge, () → Result<f32>
        println!("V={:.3} V  SOC={:.1} %", v, p);
        sleep(Duration::from_millis(1000));
    }
}
