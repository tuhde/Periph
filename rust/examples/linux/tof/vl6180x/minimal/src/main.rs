use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{Vl6180xMinimal, VL6180X_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut sensor = Vl6180xMinimal::new(dev, VL6180X_I2C_ADDRESS);   // Create VL6180X driver, (i2c, addr=0x29)
    sensor.init(&mut delay).expect("init VL6180X");                   // Run the init sequence, (delay) → ()

    loop {
        match sensor.read_range(&mut delay) {                         // Single-shot range, (delay) → u8 mm
            Ok(mm) => println!("{} mm", mm),
            Err(e) => println!("range error: {:?}", e),
        }
        sleep(Duration::from_millis(200));
    }
}
