use linux_embedded_hal::I2cdev;
use periph::chips::motor::{Drv8830Minimal, DRV8830_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut motor = Drv8830Minimal::new(dev, DRV8830_I2C_ADDRESS).expect("init DRV8830"); // Create DRV8830 driver, (i2c, addr=0x60)

    for _ in 0..5 {
        motor.drive(3.0).expect("drive"); // Drive at regulated voltage, (voltage V, + = forward) → ()
        sleep(Duration::from_secs(2));
        motor.drive(-3.0).expect("drive"); // Drive at regulated voltage, (voltage V, - = reverse) → ()
        sleep(Duration::from_secs(2));
    }

    motor.stop().expect("stop"); // Coast to standby, () → ()
}
