//! Industrial freezer monitor: the healthy range is -25 °C to -15 °C, and
//! -5 °C means the door has been left open too long. The Alert output fires
//! in interrupt mode each time the temperature leaves or re-enters the
//! window; each event is reported with the boundary that tripped. Rust has
//! no callback subscription, so the loop polls ALERT_STAT — wire
//! poll_interrupt() into an ISR on the Alert pin to do the same without
//! polling.

use linux_embedded_hal::I2cdev;
use periph::chips::temperature::{
    Mcp9808AlertMode, Mcp9808AlertOutput, Mcp9808AlertPolarity, Mcp9808Full, MCP9808_I2C_ADDRESS,
    MCP9808_SOURCE_CRITICAL, MCP9808_SOURCE_LOWER, MCP9808_SOURCE_UPPER,
};
use std::thread::sleep;
use std::time::Duration;

const MAX_ALERTS: u32 = 10;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Mcp9808Full::new(dev, MCP9808_I2C_ADDRESS).expect("init MCP9808"); // Create MCP9808 Full driver, (i2c, addr=0x18)

    // --- Trade resolution for faster sampling ---
    // 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
    // so a door opening shows up in the next reading almost immediately.
    sensor.set_resolution(0.25).expect("set_resolution"); // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → ()

    // --- Program the healthy window and the door-open threshold ---
    // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
    // 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
    sensor.set_lower_limit(-25.0).expect("set_lower_limit"); // Set TLOWER, (celsius °C) → ()
    sensor.set_upper_limit(-15.0).expect("set_upper_limit"); // Set TUPPER, (celsius °C) → ()
    sensor.set_critical_limit(-5.0).expect("set_critical_limit"); // Set TCRIT, (celsius °C) → ()
    sensor.set_hysteresis(3.0).expect("set_hysteresis"); // Set hysteresis, (celsius 0|1.5|3.0|6.0) → ()

    // --- Route every boundary to the Alert pin as a latched interrupt ---
    // Interrupt mode latches each crossing until clear_interrupt(), so a short
    // excursion is never missed between two reads.
    sensor
        .configure_alert(Mcp9808AlertMode::All, Mcp9808AlertOutput::Interrupt, Mcp9808AlertPolarity::ActiveLow)
        .expect("configure_alert"); // Configure Alert, (mode, output, polarity) → ()
    sensor.enable_alert().expect("enable_alert"); // Enable Alert output, () → ()
    println!("monitoring, {:.2} °C now", sensor.read_temperature().expect("read")); // Read ambient temperature, () → f32 °C

    // --- Report which boundary tripped, then re-arm ---
    // An empty status mask means the temperature has come back inside the
    // healthy window.
    let mut alerts = 0;
    while alerts < MAX_ALERTS {
        if sensor.is_alert_asserted().expect("is_alert_asserted") { // Check Alert output, () → bool
            let status = sensor.poll_interrupt().expect("poll_interrupt"); // Read boundary status, () → u8 mask
            let t = sensor.read_temperature().expect("read"); // Read ambient temperature, () → f32 °C
            if status & MCP9808_SOURCE_CRITICAL != 0 {
                println!("{:.2} °C  CRITICAL — door open?", t);
            } else if status & MCP9808_SOURCE_UPPER != 0 {
                println!("{:.2} °C  too warm", t);
            } else if status & MCP9808_SOURCE_LOWER != 0 {
                println!("{:.2} °C  too cold", t);
            } else {
                println!("{:.2} °C  back in range", t);
            }
            sensor.clear_interrupt().expect("clear_interrupt"); // Clear interrupt-mode Alert, () → ()
            alerts += 1;
        }
        sleep(Duration::from_secs(1));
    }

    // --- Shut down cleanly after the demo run ---
    sensor.disable_alert().expect("disable_alert"); // Disable Alert output, () → ()
}
