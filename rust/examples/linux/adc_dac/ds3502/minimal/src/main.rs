// Minimal example: set and read back a high-voltage digital potentiometer.
// Constructs the driver with RH = 15 V, RL = 0 V and steps the wiper through
// a few positions, printing the unloaded output voltage.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS3502Minimal, DS3502_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = DS3502Minimal::new(dev, DS3502_I2C_ADDRESS, 15.0, 0.0); // Create DS3502 driver, (i2c, addr=0x28, v_high=1.0 V, v_low=0.0 V)
    pot.init(&mut Delay).expect("init");                                  // Run initialization sequence, (delay) → ()

    for code in [0, 32, 64, 96, 127] {
        pot.set_wiper(code).expect("set_wiper"); // Set wiper position, (code 0–127) → ()
        println!("{} {}", code, pot.get_wiper().expect("get_wiper")); // Read wiper position, () → u8 0–127
        println!("{}", pot.get_voltage().expect("get_voltage")); // Read unloaded RW voltage, () → f32 V
        sleep(Duration::from_millis(500));
    }

    pot.set_voltage(6.0).expect("set_voltage"); // Set RW output voltage, (volts V) → ()
}
