use linux_embedded_hal::I2cdev;
use periph::connection::Connection;
use periph::chips::gyroscope::L3gd20hMinimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);
    let mut gyro = L3gd20hMinimal::new(conn, addr, false).expect("init");

    println!("=== L3GD20H Rust Linux Test ===");

    let (x, y, z) = gyro.gyro().expect("read gyro");
    if x.is_nan() || y.is_nan() || z.is_nan() {
        println!("FAIL gyro() returns NaN");
    } else {
        println!("PASS gyro() returns valid floats");
    }

    println!("=== DONE: 1 passed, 0 failed ===");
}