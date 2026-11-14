//! Thermostat with one-shot sampling: read the power-on state, sample at 4 Hz, switch to
//! 8 Hz and the 13-bit mode, take timed one-shot readings, then run an interrupt-mode
//! thermostat around the current temperature while the sensor is warmed with a finger.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::temperature::{Tmp102Full, TMP102_I2C_ADDRESS};
use std::thread::sleep;
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tmp102Full::new(dev, TMP102_I2C_ADDRESS); // Create TMP102 Full driver, (i2c, addr=0x48)
    let mut delay = Delay;

    println!("config 0x{:04X}", sensor.read_config().expect("read_config")); // Read CONFIGURATION, () → u16
    println!("limits low {} high {}", sensor.get_t_low().expect("get_t_low"),
             sensor.get_t_high().expect("get_t_high")); // Read T_LOW, () → f32 °C; Read T_HIGH, () → f32 °C

    // --- Continuous reading at 4 Hz for 5 s ---
    for _ in 0..20 {
        let t = sensor.read_temperature().expect("read_temperature"); // Read temperature, () → f32 °C
        println!("{:.4} °C (raw {})", t, sensor.read_raw().expect("read_raw")); // Read raw count, () → i16
        sleep(Duration::from_millis(250));
    }

    // --- 8 Hz and the 13-bit extended mode ---
    sensor.set_conversion_rate(8.0).ok().expect("set_conversion_rate"); // Set conversion rate, (hz) → ()
    sensor.set_extended_mode(true).expect("set_extended_mode"); // Set extended mode, (on) → ()
    sleep(Duration::from_millis(200));
    println!("extended {:.4} °C", sensor.read_temperature().expect("read_temperature")); // Read temperature, () → f32 °C
    sensor.set_extended_mode(false).expect("set_extended_mode"); // Set extended mode, (on) → ()
    sensor.set_conversion_rate(4.0).ok().expect("set_conversion_rate"); // Set conversion rate, (hz) → ()

    // --- Shutdown and timed one-shot readings ---
    sensor.shutdown().expect("shutdown"); // Shut down, () → ()
    for _ in 0..5 {
        let start = Instant::now();
        let t = sensor.one_shot(&mut delay).ok().expect("one_shot"); // One-shot reading, (delay) → f32 °C
        println!("one-shot {:.4} °C in {} ms", t, start.elapsed().as_millis());
    }
    sensor.wake().expect("wake"); // Wake, () → ()

    // --- Interrupt-mode thermostat around the current temperature ---
    let t = sensor.read_temperature().expect("read_temperature"); // Read temperature, () → f32 °C
    sensor.set_t_high(t + 1.0).expect("set_t_high"); // Set T_HIGH, (celsius °C) → ()
    sensor.set_t_low(t - 1.0).expect("set_t_low"); // Set T_LOW, (celsius °C) → ()
    sensor.set_fault_queue(2).ok().expect("set_fault_queue"); // Set fault queue, (count) → ()
    sensor.set_polarity(false).expect("set_polarity"); // Set ALERT polarity, (active_high) → ()
    sensor.set_thermostat_mode(true).expect("set_thermostat_mode"); // Set thermostat mode, (interrupt) → ()
    println!("warm the sensor with a finger ...");
    for _ in 0..40 {
        if sensor.poll_interrupt().expect("poll_interrupt") { // Poll the alert state, () → bool
            println!("ALERT: {:.2} °C, alert_state={}", sensor.read_temperature().expect("read_temperature"),
                     sensor.alert_state().expect("alert_state")); // Read alert state, () → bool
        }
        sleep(Duration::from_millis(250));
    }

    // --- Restore the power-on defaults ---
    sensor.set_thermostat_mode(false).expect("set_thermostat_mode"); // Set thermostat mode, (interrupt) → ()
    sensor.set_fault_queue(1).ok().expect("set_fault_queue"); // Set fault queue, (count) → ()
    sensor.set_t_high(80.0).expect("set_t_high"); // Set T_HIGH, (celsius °C) → ()
    sensor.set_t_low(75.0).expect("set_t_low"); // Set T_LOW, (celsius °C) → ()
    sensor.write_config(0x60A0).expect("write_config"); // Write CONFIGURATION, (value) → ()
    sensor.reset().expect("reset"); // General-call reset, () → ()
}
