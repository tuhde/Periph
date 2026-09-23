//! Exercises every method in the MCP9808 public API: temperature,
//! resolution, Shutdown mode, the three boundaries and hysteresis, the Alert
//! output, and the boundary-status poll. The one-way lock methods are shown
//! but left commented out — they cannot be undone without a power-on reset.

use linux_embedded_hal::I2cdev;
use periph::chips::temperature::{
    Mcp9808AlertMode, Mcp9808AlertOutput, Mcp9808AlertPolarity, Mcp9808Full, MCP9808_I2C_ADDRESS,
    MCP9808_SOURCE_CRITICAL, MCP9808_SOURCE_LOWER, MCP9808_SOURCE_UPPER,
};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Mcp9808Full::new(dev, MCP9808_I2C_ADDRESS).expect("init MCP9808"); // Create MCP9808 Full driver, (i2c, addr=0x18)
                                                                                       // checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04

    let t = sensor.read_temperature().expect("read"); // Read ambient temperature, () → f32 °C
                                                      // masks TA's 3 status bits, decodes 1/16 °C two's complement
    println!("temperature {:.4} °C", t);

    sensor.set_resolution(0.25).expect("set_resolution"); // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → ()
                                                          // 0.25 °C step converts in ~65 ms instead of 250 ms
    println!("resolution {} °C", sensor.get_resolution().expect("get_resolution")); // Read resolution, () → f32 °C
                                                                                   // decodes the RESOLUTION register code

    sensor.shutdown().expect("shutdown"); // Enter Shutdown mode, () → ()
                                          // stops conversion; TA keeps its last value
    println!("shutdown {}", sensor.is_shutdown().expect("is_shutdown")); // Check Shutdown mode, () → bool
                                                                        // reads CONFIG.SHDN
    sensor.wake().expect("wake"); // Leave Shutdown mode, () → ()
                                  // resumes continuous conversion
    sleep(Duration::from_millis(100));

    sensor.set_upper_limit(30.0).expect("set_upper_limit"); // Set TUPPER, (celsius °C) → ()
                                                            // rounded to the nearest 0.25 °C step
    sensor.set_lower_limit(10.0).expect("set_lower_limit"); // Set TLOWER, (celsius °C) → ()
                                                            // rounded to the nearest 0.25 °C step
    sensor.set_critical_limit(45.0).expect("set_critical_limit"); // Set TCRIT, (celsius °C) → ()
                                                                  // rounded to the nearest 0.25 °C step
    println!("upper {}", sensor.get_upper_limit().expect("get_upper_limit")); // Read TUPPER, () → f32 °C
                                                                             // decodes the 0.25 °C two's-complement boundary
    println!("lower {}", sensor.get_lower_limit().expect("get_lower_limit")); // Read TLOWER, () → f32 °C
                                                                             // decodes the 0.25 °C two's-complement boundary
    println!("critical {}", sensor.get_critical_limit().expect("get_critical_limit")); // Read TCRIT, () → f32 °C
                                                                                      // decodes the 0.25 °C two's-complement boundary

    sensor.set_hysteresis(1.5).expect("set_hysteresis"); // Set hysteresis, (celsius 0|1.5|3.0|6.0) → ()
                                                         // applied on the cooling edge of each boundary only
    println!("hysteresis {}", sensor.get_hysteresis().expect("get_hysteresis")); // Read hysteresis, () → f32 °C
                                                                                // decodes CONFIG.THYST

    // sensor.lock_critical_limit().expect("lock"); // Lock TCRIT, () → ()
    //                                              // irreversible until power-on reset
    // sensor.lock_window_limits().expect("lock");  // Lock TUPPER/TLOWER, () → ()
    //                                              // irreversible until power-on reset
    println!("crit locked {}", sensor.is_critical_limit_locked().expect("locked")); // Check TCRIT lock, () → bool
                                                                                   // reads CONFIG.CRIT_LOCK
    println!("win locked {}", sensor.is_window_limits_locked().expect("locked")); // Check TUPPER/TLOWER lock, () → bool
                                                                                 // reads CONFIG.WIN_LOCK

    sensor
        .configure_alert(Mcp9808AlertMode::All, Mcp9808AlertOutput::Interrupt, Mcp9808AlertPolarity::ActiveLow)
        .expect("configure_alert"); // Configure Alert, (mode, output, polarity) → ()
                                    // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
    sensor.enable_alert().expect("enable_alert"); // Enable Alert output, () → ()
                                                  // sets CONFIG.ALERT_CNT
    println!("alert asserted {}", sensor.is_alert_asserted().expect("is_alert_asserted")); // Check Alert output, () → bool
                                                                                          // reads the read-only CONFIG.ALERT_STAT

    let status = sensor.poll_interrupt().expect("poll_interrupt"); // Read boundary status, () → u8 mask
                                                                   // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
    println!(
        "below lower {} above upper {} critical {}",
        status & MCP9808_SOURCE_LOWER != 0,
        status & MCP9808_SOURCE_UPPER != 0,
        status & MCP9808_SOURCE_CRITICAL != 0
    );
    sensor.clear_interrupt().expect("clear_interrupt"); // Clear interrupt-mode Alert, () → ()
                                                        // writes CONFIG.INT_CLEAR=1; no effect in comparator mode
    sensor.disable_alert().expect("disable_alert"); // Disable Alert output, () → ()
                                                    // clears CONFIG.ALERT_CNT
}
