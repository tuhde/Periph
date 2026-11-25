//! Demo for the FXOS8700 — a tilt-compensated electronic compass with motion
//! wake-up.
//!
//! Hybrid mode at 100 Hz per sensor, ±4 *g*, magnetometer OSR 7. Run the
//! self-test once, enable the hardware hard-iron calibration for 15 s while
//! the board is rotated (printing the min/max span every second), then print
//! the tilt-compensated heading with pitch and roll every 250 ms. Orientation
//! changes and motion are reported from `poll_interrupt`; Auto-SLEEP drops the
//! chip to a low rate after 10 s of inactivity. Runs for 60 s then exits.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Fxos8700Full, FXOS8700_SOURCE_FF_MT, FXOS8700_SOURCE_ORIENTATION,
};
use periph::connection::Connection;
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create FXOS8700 Full driver, (connection, addr=0x1E, delay) → Result
    let mut chip = Fxos8700Full::new(Connection::new(dev), addr, Delay).expect("init FXOS8700");

    let directions = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                      "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
    let orientations = ["PORTRAIT UP", "PORTRAIT DOWN", "LANDSCAPE RIGHT", "LANDSCAPE LEFT"];
    let declination = 3.0f32;     // magnetic declination of the site in degrees

    // --- Configure hybrid mode ---
    // ±4 g keeps tilt maths precise while tolerating handling; magnetometer
    // oversampling ratio 7 gives the lowest noise at this data rate.
    // Set accelerometer range, (range_g 2|4|8) → Result<bool>
    chip.set_range(4).expect("set_range");
    // Set magnetometer oversampling, (ratio 0..7, sleep_ratio=None) → Result<bool>
    chip.set_mag_oversampling(7, None).expect("set_mag_oversampling");

    // --- Check the accelerometer ---
    // The electrostatic self-test must move every axis by the datasheet minimum.
    // Run accelerometer self-test, () → (bool, i16, i16, i16) counts
    let (ok, dx, dy, dz) = chip.self_test().expect("self_test");
    println!("self-test {} {} {} {}", if ok { "OK" } else { "FAILED" }, dx, dy, dz);

    // --- Calibrate the hard-iron offsets ---
    // Auto-calibration writes the midpoint of the tracked min/max to the
    // offset registers every cycle; rotate the board through all
    // orientations meanwhile.
    // Hardware hard-iron calibration, (enabled) → Result
    chip.set_mag_auto_calibration(true).expect("set_mag_auto_calibration");
    println!("Rotate the board in all directions for 15 s ...");
    for _ in 0..15 {
        std::thread::sleep(Duration::from_secs(1));
        // Read tracked min / max, () → ((f32, f32, f32), (f32, f32, f32)) µT
        let (mn, mx) = chip.read_mag_min_max().expect("read_mag_min_max");
        println!("span {:.1} {:.1} {:.1} uT", mx.0 - mn.0, mx.1 - mn.1, mx.2 - mn.2);
    }
    // Hardware hard-iron calibration, (enabled) → Result
    chip.set_mag_auto_calibration(false).expect("set_mag_auto_calibration");
    // Get hard-iron offsets, () → (f32, f32, f32) µT
    println!("offsets {:?}", chip.get_mag_offset().expect("get_mag_offset"));

    // --- Arm orientation and motion detection ---
    // Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
    // chip to a low rate after 10 s without motion, and motion wakes it again.
    // Configure orientation detection, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → Result<bool>
    chip.set_orientation(true, 100.0, 45, 4, 4, 2).expect("set_orientation");
    // Configure freefall / motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → Result
    chip.set_freefall_motion(true, 7, 0.3, 60.0, true, false).expect("set_freefall_motion");
    // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → Result<bool>
    chip.set_auto_sleep(true, 12.5, 10.0, FXOS8700_SOURCE_FF_MT).expect("set_auto_sleep");
    // Enable and route interrupt, (source mask, pin 1|2) → Result<bool>
    chip.enable_interrupt(FXOS8700_SOURCE_ORIENTATION | FXOS8700_SOURCE_FF_MT, 1).expect("enable_interrupt");

    // --- Print the heading ---
    // Every 250 ms the tilt-compensated heading is shown with the board's
    // pitch and roll; the temperature is printed once per second.
    let start = Instant::now();
    let mut last_temperature = Instant::now() - Duration::from_secs(1);
    while start.elapsed() < Duration::from_secs(60) {
        // Compute tilt-compensated heading, (declination_deg) → f32 degrees
        let heading = chip.heading(declination).expect("heading");
        // Read both sensors, () → Option<((f32, f32, f32), (f32, f32, f32))>
        if let Some(((ax, ay, az), _)) = chip.read_hybrid().expect("read_hybrid") {
            let roll = ay.atan2(az).to_degrees();
            let pitch = (-ax).atan2((ay * ay + az * az).sqrt()).to_degrees();
            println!("{:5.1} deg {:3} pitch {:5.1} roll {:5.1}",
                     heading, directions[(((heading + 11.25) / 22.5) as usize) % 16], pitch, roll);
        }
        // Poll and clear interrupt sources, () → u8
        let status = chip.poll_interrupt().expect("poll_interrupt");
        if status & FXOS8700_SOURCE_ORIENTATION != 0 {
            // Read orientation, () → (changed, lockout, portrait_landscape, back)
            let (_, _, pl, _) = chip.read_orientation().expect("read_orientation");
            println!("{}", orientations[pl as usize]);
        }
        if status & FXOS8700_SOURCE_FF_MT != 0 {
            // Read SYSMOD, () → u8 0 standby | 1 wake | 2 sleep
            println!("MOTION, system mode {}", chip.read_system_mode().expect("read_system_mode"));
        }
        if last_temperature.elapsed() >= Duration::from_secs(1) {
            last_temperature = Instant::now();
            // Read die temperature, () → Option<f32> °C
            if let Some(t) = chip.read_temperature().expect("read_temperature") {
                println!("temperature {:.1} C", t);
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}
