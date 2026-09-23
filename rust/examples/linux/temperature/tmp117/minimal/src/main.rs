use linux_embedded_hal::I2cdev;
use periph::chips::temperature::{Tmp117Minimal, TMP117_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tmp117Minimal::new(dev, TMP117_I2C_ADDRESS).expect("init TMP117"); // Create TMP117 driver, (i2c, addr=0x48)

    loop {
        let t = sensor.read_temperature().expect("read_temperature"); // Read temperature, () → f32 °C
        println!("{:.4} °C", t);
        sleep(Duration::from_secs(1));
    }
}
