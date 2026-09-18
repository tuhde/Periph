use linux_embedded_hal::I2cdev;
use periph::connection::Connection;
use periph::chips::gyroscope::{L3gd20hFull, ODR_190_HZ, FS_500_DPS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);
    let mut gyro = L3gd20hFull::new(conn, addr, false).expect("init");

    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    // 190 Hz ODR provides good temporal resolution for shake detection;
    // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
    // detecting moderate to strong motion without clipping.
    gyro.configure(ODR_190_HZ, 0, FS_500_DPS).expect("configure");

    println!("L3GD20H shake detector running. Shake the device...");

    loop {
        if gyro.data_ready().expect("data_ready") { // Check data ready, () -> Result<bool>
            let (x, y, z) = gyro.gyro().expect("gyro"); // Read angular rate, () -> Result<(f32, f32, f32)>
            let magnitude = libm::sqrtf(x*x + y*y + z*z);
            if magnitude > 1.0 {
                println!("SHAKE DETECTED: mag={:.3} (x={:.3} y={:.3} z={:.3})", magnitude, x, y, z);
            } else {
                println!("x={:.3} y={:.3} z={:.3} mag={:.3}", x, y, z, magnitude);
            }
        }
    }
}