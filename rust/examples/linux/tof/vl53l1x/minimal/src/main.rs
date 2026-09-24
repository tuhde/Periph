use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{Vl53l1xMinimal, VL53L1X_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Vl53l1xMinimal::new(dev, VL53L1X_I2C_ADDRESS, Delay).expect("init VL53L1X"); // Create VL53L1X driver, (i2c, addr=0x29, delay)

    loop {
        let d = sensor.distance().expect("distance"); // Measure distance, () → u16 mm
        if sensor.range_valid() {                     // Check last measurement, () → bool
            println!("{} mm", d);
        } else {
            println!("out of range");
        }
        sleep(Duration::from_millis(200));
    }
}
