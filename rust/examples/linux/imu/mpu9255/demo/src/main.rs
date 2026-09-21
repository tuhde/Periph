use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::imu::Mpu9255Full;
use std::thread::sleep;
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Mpu9255Full::new(dev, addr, &mut delay).expect("init MPU9255"); // Create MPU9255 driver, (i2c, addr, delay) → Result
    chip.configure_wake_on_motion(64, 31.25, &mut delay).expect("configure_wake_on_motion"); // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25, delay) → Result

    let mut last_heartbeat = Instant::now();
    loop {
        // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
        while !chip.motion_detected().expect("motion_detected") {            // Check motion detected, () → bool
            if last_heartbeat.elapsed() >= Duration::from_secs(1) {
                println!("sleeping...");
                last_heartbeat = Instant::now();
            }
            sleep(Duration::from_millis(200));
        }

        // --- Wake phase: re-arm the full 6-axis + mag stack ---
        chip.set_sleep(false).expect("set_sleep");                          // Wake from sleep, (sleep=true) → Result
        chip.configure_gyro(1).expect("configure_gyro");                     // Configure gyro range, (full_scale=0) → Result
        chip.configure_accel(1).expect("configure_accel");                   // Configure accel range, (full_scale=0) → Result
        chip.enable_mag(16, 6, &mut delay).expect("enable_mag");             // Initialize magnetometer, (bits=16, mode=6, delay) → Result

        // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
        println!("--- motion detected ---");
        let end = Instant::now() + Duration::from_secs(5);
        while Instant::now() < end {
            while !chip.data_ready().expect("data_ready") {}                // Check data ready flag, () → bool

            let (ax, ay, az) = chip.accel().expect("accel");                // Read 3-axis acceleration, () → (f32, f32, f32) m/s²
            let (gx, gy, gz) = chip.gyro().expect("gyro");                  // Read 3-axis angular rate, () → (f32, f32, f32) rad/s
            let (mx, my, mz) = chip.mag().expect("mag");                    // Read 3-axis magnetic field, () → (f32, f32, f32) µT

            let roll = ay.atan2(az) * 180.0 / core::f32::consts::PI;
            let pitch = (-ax).atan2((ay * ay + az * az).sqrt()) * 180.0 / core::f32::consts::PI;
            let heading = my.atan2(mx) * 180.0 / core::f32::consts::PI;
            let gyro_mag = (gx * gx + gy * gy + gz * gz).sqrt();

            println!("roll: {:6.1}  pitch: {:6.1}  heading: {:6.1}  |g|: {:.2}",
                     roll, pitch, heading, gyro_mag);
            sleep(Duration::from_millis(100));
        }

        // --- Return to low-power wake-on-motion mode ---
        chip.configure_wake_on_motion(64, 31.25, &mut delay).expect("configure_wake_on_motion"); // Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25, delay) → Result
        last_heartbeat = Instant::now();
    }
}