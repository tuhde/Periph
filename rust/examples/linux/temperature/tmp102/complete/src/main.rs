//! Exercises every method in the TMP102 Full API; the power-on limits are restored at the end.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::temperature::{Tmp102Full, TMP102_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tmp102Full::new(dev, TMP102_I2C_ADDRESS); // Create TMP102 Full driver, (i2c, addr=0x48)
    let mut delay = Delay;

    let t = sensor.read_temperature().expect("read_temperature"); // Read temperature, () → f32 °C
                                                                  // decodes TEMPERATURE, 0.0625 °C two's complement
    println!("temperature {:.4} °C", t);
    println!("raw {}", sensor.read_raw().expect("read_raw")); // Read raw count, () → i16
                                                              // signed 12/13-bit count, 0.0625 °C per LSB
    let config = sensor.read_config().expect("read_config"); // Read CONFIGURATION, () → u16
                                                             // reset value 0x60A0
    println!("config 0x{:04X}", config);
    sensor.write_config(config).expect("write_config"); // Write CONFIGURATION, (value) → ()
                                                        // read-only bits are masked out

    sensor.set_conversion_rate(8.0).ok().expect("set_conversion_rate"); // Set conversion rate, (hz 0.25|1|4|8) → ()
    sensor.set_extended_mode(true).expect("set_extended_mode"); // Set extended mode, (on) → ()
                                                                // EM bit, 13-bit readings up to +255.9375 °C
    sleep(Duration::from_millis(200));
    println!("extended {:.4} °C", sensor.read_temperature().expect("read_temperature"));
    sensor.set_extended_mode(false).expect("set_extended_mode"); // Set extended mode, (on) → ()
                                                                 // back to 12 bit

    sensor.set_t_high(30.0).expect("set_t_high"); // Set T_HIGH, (celsius °C) → ()
                                                  // rounded to 0.0625 °C
    sensor.set_t_low(10.0).expect("set_t_low"); // Set T_LOW, (celsius °C) → ()
                                                // rounded to 0.0625 °C
    println!("high {}", sensor.get_t_high().expect("get_t_high")); // Read T_HIGH, () → f32 °C
                                                                   // same format as TEMPERATURE
    println!("low {}", sensor.get_t_low().expect("get_t_low")); // Read T_LOW, () → f32 °C
                                                                // same format as TEMPERATURE

    sensor.set_fault_queue(2).ok().expect("set_fault_queue"); // Set fault queue, (count 1|2|4|6) → ()
                                                              // consecutive faults before ALERT
    sensor.set_polarity(false).expect("set_polarity"); // Set ALERT polarity, (active_high) → ()
                                                       // POL bit, false = active low
    sensor.set_thermostat_mode(false).expect("set_thermostat_mode"); // Set thermostat mode, (interrupt) → ()
                                                                     // TM bit, false = comparator
    println!("alert {}", sensor.alert_state().expect("alert_state")); // Read alert state, () → bool
                                                                      // AL bit corrected for POL

    sensor.shutdown().expect("shutdown"); // Shut down, () → ()
                                          // SD bit, stops after the current conversion
    let one_shot = sensor.one_shot(&mut delay).ok().expect("one_shot"); // One-shot reading, (delay) → f32 °C
                                                                        // OS=1, polls until done, restores the mode
    println!("one-shot {:.4} °C", one_shot);
    sensor.wake().expect("wake"); // Wake, () → ()
                                  // resume continuous conversion

    println!("polling {}", sensor.poll_interrupt().expect("poll_interrupt")); // Poll the alert state, () → bool
                                                                              // reads CONFIGURATION and TEMPERATURE

    sensor.set_conversion_rate(4.0).ok().expect("set_conversion_rate"); // Set conversion rate, (hz) → ()
                                                                        // back to the power-on default
    sensor.set_t_high(80.0).expect("set_t_high"); // Set T_HIGH, (celsius °C) → ()
    sensor.set_t_low(75.0).expect("set_t_low"); // Set T_LOW, (celsius °C) → ()
    sensor.reset().expect("reset"); // General-call reset, () → ()
                                    // resets every general-call device on the bus
}
