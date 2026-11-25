// Minimal example: bring up the headphone output and set the volume with CS43L22Minimal.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{CS43L22Minimal, CS43L22_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = CS43L22Minimal::new(dev, CS43L22_I2C_ADDRESS); // Create CS43L22 driver, (i2c, addr=0x4A)
    dac.init().expect("init"); // Verify ID, load defaults, () → ()
    dac.start().expect("start"); // Power up the DAC once MCLK is running, () → ()
    dac.set_volume(-20.0).expect("set_volume"); // Set master volume, (db -102–12 dB) → ()
    dac.mute(false).expect("mute"); // Unmute the headphone outputs, (on) → ()
    for _ in 0..5 {
        println!("{}", dac.get_volume().expect("get_volume")); // Read back master volume, () → f32 dB
        sleep(Duration::from_secs(1));
    }
    dac.stop(&mut Delay).expect("stop"); // Power down the DAC, (delay) → ()
}
