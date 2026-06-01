use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::imu::Mpu6050Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;

    // --- Configure for motion logging with moderate dynamic range ---
    // ±4g captures typical tilting and handling forces without clipping;
    // ±500 dps covers fast rotations while retaining sub-degree resolution.
    let mut chip = Mpu6050Full::new(dev, addr, &mut delay).expect("init MPU6050"); // Create MPU6050 driver, (i2c, addr, delay) → Result
    chip.configure_accel(1).expect("configure_accel");    // Configure accel range, (full_scale=0) → Result
    chip.configure_gyro(1).expect("configure_gyro");      // Configure gyro range, (full_scale=0) → Result

    println!("{:<8} {:<8} {:<10} {:<10}", "roll", "pitch", "|accel|", "|gyro|");

    loop {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while !chip.data_ready().unwrap_or(false) {}      // Check data ready flag, () → bool

        let (ax, ay, az) = chip.accel().expect("accel"); // Read 3-axis acceleration, () → (f32, f32, f32) m/s²
        let (gx, gy, gz) = chip.gyro().expect("gyro");   // Read 3-axis angular rate, () → (f32, f32, f32) rad/s

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        let roll  = libm::atan2f(ay, az) * 180.0 / core::f32::consts::PI;
        let pitch = libm::atan2f(-ax, libm::sqrtf(ay * ay + az * az)) * 180.0 / core::f32::consts::PI;
        let accel_mag = libm::sqrtf(ax * ax + ay * ay + az * az);
        let gyro_mag  = libm::sqrtf(gx * gx + gy * gy + gz * gz);

        println!("{:<8.1} {:<8.1} {:<10.3} {:<10.3}", roll, pitch, accel_mag, gyro_mag);
        sleep(Duration::from_millis(100));
    }
}
