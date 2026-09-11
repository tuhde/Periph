use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Adxl345Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x53);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut accel = Adxl345Minimal::new(dev, addr, false).expect("init ADXL345"); // Create ADXL345 driver, (i2c, addr=0x53, spi=false)

    for _ in 0..10 {
        let (x, y, z) = accel.read().expect("read acceleration");   // Read 3-axis acceleration, () → (f32, f32, f32) g
        println!("x={:.3} y={:.3} z={:.3} g", x, y, z);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}