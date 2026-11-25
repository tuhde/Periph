//! Demo for the ICM-20948 — motion and heading monitor.
//!
//! Configure ±4 g / ±500 dps with `DLPFCFG` 3 and a ~100 Hz sample rate, enable the magnetometer,
//! arm wake-on-motion at 200 mg and stream accelerometer + gyroscope into the FIFO. Every 100 ms
//! print roll, pitch, the tilt-compensated heading and the gyro rate magnitude; once per second
//! drain the FIFO and count 12-byte frames; print MOTION when `poll_interrupt` reports
//! wake-on-motion. Runs for 60 s then exits.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::icm20948::{Icm20948Full, SOURCE_WAKE_ON_MOTION};
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    // Create ICM20948 driver, (i2c, addr=0x68, delay) → Result
    let mut imu = Icm20948Full::new(dev, addr, &mut delay).expect("init");

    // --- Configure for motion and heading monitoring ---
    // ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
    // 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The
    // magnetometer runs through the chip's own I²C master, so one connection
    // is enough.
    // Configure accel range, (full_scale) → Result
    imu.configure_accel(1).expect("configure accel");
    // Configure gyro range, (full_scale) → Result
    imu.configure_gyro(1).expect("configure gyro");
    // Configure low-pass filters, (gyro_dlpf, accel_dlpf, gyro_filter, accel_filter) → Result
    imu.configure_filters(3, 3, true, true).expect("configure filters");
    // Configure sample rate, (gyro_div, accel_div) → Result
    imu.configure_sample_rate(10, 10).expect("configure sample rate");
    // Enable magnetometer, (delay) → Result
    imu.enable_mag(&mut delay).expect("enable mag");
    // Read die temperature, () → Result<f32> °C
    let temp0 = imu.temperature().expect("read temp");
    println!("ICM-20948 die temperature: {:.1} C", temp0);

    // --- Arm wake-on-motion and route it to the interrupt status ---
    // A 200 mg threshold flags a bump; poll_interrupt() reports it as
    // SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
    // Arm wake-on-motion, (threshold_mg, enabled) → Result
    imu.set_wake_on_motion(200.0, true).expect("wake on motion");
    // Enable interrupt source, (source) → Result
    imu.enable_interrupt(SOURCE_WAKE_ON_MOTION).expect("enable interrupt");

    // --- Stream accelerometer + gyro into the FIFO ---
    // Stream mode overwrites the oldest data; once per second the FIFO is
    // drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
    // Enable FIFO sources, (accel, gyro, temp) → Result
    imu.enable_fifo(true, true, false).expect("enable fifo");
    // Select FIFO mode, (snapshot) → Result
    imu.set_fifo_mode(false).expect("fifo mode");

    println!("roll     pitch    heading  |gyro|");

    let start = Instant::now();
    let mut last_fifo = start;
    while start.elapsed() < Duration::from_secs(60) {
        // Read one coherent sample, () → Result<((f32, f32, f32), (f32, f32, f32), f32)> m/s², rad/s, °C
        let ((ax, ay, az), (gx, gy, gz), _temp) = imu.read_all().expect("read_all");
        // Read 3-axis magnetic field, () → Result<(f32, f32, f32)> µT
        let (mx, my, mz) = imu.mag().expect("read mag");

        // --- Compute roll, pitch and a tilt-compensated heading ---
        // The magnetometer frame is rotated 180° about X relative to the
        // inertial frame, so (mx, -my, -mz) maps it into the accel frame
        // before the tilt compensation rotates it into the horizontal plane.
        let roll = ay.atan2(az);
        let pitch = (-ax).atan2((ay * ay + az * az).sqrt());
        let (bx, by, bz) = (mx, -my, -mz);
        let xh = bx * pitch.cos() + bz * pitch.sin();
        let yh = bx * roll.sin() * pitch.sin() + by * roll.cos() - bz * roll.sin() * pitch.cos();
        let heading = ((-yh).atan2(xh).to_degrees() + 360.0) % 360.0;
        let gyro_mag = (gx * gx + gy * gy + gz * gz).sqrt();
        println!("{:<8.1} {:<8.1} {:<8.1} {:<8.3}", roll.to_degrees(), pitch.to_degrees(), heading, gyro_mag);

        // Read interrupt status, () → Result<u8>
        let status = imu.poll_interrupt().expect("poll interrupt");
        if status & SOURCE_WAKE_ON_MOTION != 0 {
            println!("MOTION");
        }

        if last_fifo.elapsed() >= Duration::from_secs(1) {
            last_fifo = Instant::now();
            let mut fifo = [0u8; 240];
            // Read FIFO data, (buf) → Result<usize>
            let got = imu.read_fifo(&mut fifo).expect("read fifo");
            println!("FIFO: {} bytes, {} frames", got, got / 12);
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}
