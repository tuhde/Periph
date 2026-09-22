use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::Mpu9250Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;

    // --- Configure for noise-sensitive power rail monitoring ---
    // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
    // continuous mode avoids re-triggering overhead between measurements.
    let mut imu = Mpu9250Full::new(dev, addr, &mut delay).expect("init");
    imu.configure_accel(1).expect("configure accel");
    imu.configure_gyro(1).expect("configure gyro");
    imu.enable_mag(16, 6, &mut delay).expect("enable mag");

    println!("roll     pitch    heading  |accel|  |gyro|");

    loop {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while !imu.data_ready().expect("data ready") {
            std::thread::sleep(std::time::Duration::from_millis(1));
        }

        let (ax, ay, az) = imu.accel().expect("read accel");
        let (gx, gy, gz) = imu.gyro().expect("read gyro");
        let (mx, my, mz) = imu.mag().expect("read mag");

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        let roll  = ay.atan2(az) * 180.0 / std::f32::consts::PI;
        let pitch = (-ax).atan2((ay * ay + az * az).sqrt()) * 180.0 / std::f32::consts::PI;

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        let heading = my.atan2(mx) * 180.0 / std::f32::consts::PI;

        let accel_mag = (ax * ax + ay * ay + az * az).sqrt();
        let gyro_mag  = (gx * gx + gy * gy + gz * gz).sqrt();

        println!("{:.1}      {:.1}      {:.1}      {:.3}    {:.3}", roll, pitch, heading, accel_mag, gyro_mag);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}