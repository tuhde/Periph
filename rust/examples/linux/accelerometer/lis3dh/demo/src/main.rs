//! Demo for the LIS3DH — tilt, double-tap and free-fall monitor.
//!
//! Configure ±2 *g*, 50 Hz, high-resolution mode, run the self-test once,
//! arm a Z-axis double tap (0.8 g) and a free-fall detector (0.35 g / 60 ms)
//! both latched and routed to INT1, and run the FIFO in stream mode with a
//! 16-sample watermark. Drain the FIFO every 100 ms, average the samples and
//! print pitch / roll, the vector magnitude and the relative temperature;
//! print DOUBLE TAP or FREE FALL whenever `poll_interrupt` reports the
//! respective source. Runs for 60 s then exits.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Lis3dhFull, LIS3DH_FIFO_STREAM, LIS3DH_MODE_HIGH_RESOLUTION, LIS3DH_SOURCE_CLICK,
    LIS3DH_SOURCE_IA1,
};
use periph::connection::Connection;
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x18);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create LIS3DH Full driver, (connection, addr=0x18, delay) → Result
    let mut chip = Lis3dhFull::new(Connection::new(dev), addr, Delay).expect("init LIS3DH");

    // --- Configure ±2 g / 50 Hz high-resolution and verify the part ---
    // The self-test only passes on healthy silicon, so run it before arming
    // anything; 50 Hz keeps the self-test window short (2 samples).
    // Set output data rate, (rate_hz=100) → Result
    chip.set_data_rate(50).expect("set_data_rate");
    // Set full-scale range, (range_g=2) → Result
    chip.set_range(2).expect("set_range");
    // Set operating mode, (mode=2) → Result
    chip.set_mode(LIS3DH_MODE_HIGH_RESOLUTION).expect("set_mode");
    // Run self-test, (mode) → bool
    let ok = chip.self_test(1).expect("self_test");
    println!("self-test: {}", if ok { "PASS" } else { "FAIL" });

    // --- Arm the double-tap and free-fall detectors on INT1 ---
    // Both sources are latched so a transient cannot slip past the 100 ms
    // polling loop; the click window (200 ms) covers a slow double tap.
    // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms, latch) → Result
    chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0, true).expect("set_click");
    // double tap on the Z axis at 0.8 g, latched until CLICK_SRC is read
    // Configure free-fall, (generator, threshold_g, duration_ms) → Result
    chip.set_free_fall(1, 0.35, 60.0).expect("set_free_fall");
    // 0.35 g for 60 ms on generator 1
    // Latch generator, (generator, enabled) → Result
    chip.set_latch(1, true).expect("set_latch");
    // LIR_INT1 — the free-fall event is held until INT1_SRC is read
    // Route source to INT pin, (source, pin) → Result
    chip.enable_interrupt(LIS3DH_SOURCE_CLICK, 1).expect("enable_interrupt");
    // click interrupt → INT1 (I1_CLICK)
    // Route source to INT pin, (source, pin) → Result
    chip.enable_interrupt(LIS3DH_SOURCE_IA1, 1).expect("enable_interrupt");
    // generator 1 → INT1 (I1_IA1)

    // --- Run the FIFO in stream mode with a 16-sample watermark ---
    // Stream mode overwrites the oldest samples once full, so a slow reader
    // always sees recent motion instead of going stale.
    // Configure FIFO, (mode, watermark, trigger_int2) → Result
    chip.set_fifo(LIS3DH_FIFO_STREAM, 16, false).expect("set_fifo");
    // stream mode, 16-sample watermark

    let start = Instant::now();
    let mut last_sample = start;

    // --- 60-second tilt / tap / free-fall monitor ---
    // The board is tilted, double-tapped and dropped within the 60 s window.
    // Between events the vector magnitude sits at ≈1.00 g (gravity).
    while start.elapsed() < Duration::from_secs(60) {
        let now = Instant::now();

        // Drain the FIFO every 100 ms and characterise the average attitude.
        if now.duration_since(last_sample) >= Duration::from_millis(100) {
            // Drain FIFO, (count) → Vec<(f32, f32, f32)> g
            let samples = chip.read_fifo(None).expect("read_fifo");
            // Read FIFO level, () → u8
            let level = chip.fifo_level().expect("fifo_level");
            if !samples.is_empty() {
                let n = samples.len() as f32;
                let mut ax = 0.0f32;
                let mut ay = 0.0f32;
                let mut az = 0.0f32;
                for s in &samples {
                    ax += s.0;
                    ay += s.1;
                    az += s.2;
                }
                ax /= n;
                ay /= n;
                az /= n;
                let mag = (ax * ax + ay * ay + az * az).sqrt();
                let pitch = (ax as f64).atan2(((ay * ay + az * az) as f64).sqrt()).to_degrees();
                let roll = (ay as f64).atan2(az as f64).to_degrees();
                // Read relative temperature, () → i8 °C
                let temp = chip.read_temperature_delta().expect("read_temperature_delta");
                println!(
                    "pitch={:+6.1} roll={:+6.1} |a|={:.3} g  T={} °C  fifo={}",
                    pitch, roll, mag, temp, level
                );
            }

            // Read interrupt status, () → u16
            let status = chip.poll_interrupt().expect("poll_interrupt");
            if status & LIS3DH_SOURCE_CLICK != 0 {
                println!("DOUBLE TAP");
            }
            if status & LIS3DH_SOURCE_IA1 != 0 {
                println!("FREE FALL");
            }
            last_sample = now;
        }

        std::thread::sleep(Duration::from_millis(10));
    }
    println!("done");
}
