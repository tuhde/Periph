// Minimal example: read the illuminance once a second (1× gain, 402 ms, T/FN/CL coefficients).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{Tsl2561Minimal, TSL2561_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tsl2561Minimal::new(dev, TSL2561_I2C_ADDRESS);  // Create TSL2561 driver, (i2c, addr=0x39)
    sensor.init(&mut Delay).expect("init");                          // Run initialization sequence, (delay) → ()

    for _ in 0..10 {
        println!("{:.1} lx", sensor.read_lux().expect("lux"));      // Read illuminance, () → f32 lux
        sleep(Duration::from_secs(1));
    }
}
