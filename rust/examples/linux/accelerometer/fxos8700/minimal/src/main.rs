use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::Fxos8700Minimal;
use periph::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create FXOS8700 driver, (connection, addr=0x1E, delay) → Result
    let mut chip = Fxos8700Minimal::new(Connection::new(dev), addr, Delay).expect("init FXOS8700");

    loop {
        // Read acceleration, () → (f32, f32, f32) g
        let (ax, ay, az) = chip.read_accel().expect("read_accel");
        // Read magnetic field, () → (f32, f32, f32) µT
        let (mx, my, mz) = chip.read_mag().expect("read_mag");
        println!("accel x={:.4} y={:.4} z={:.4} g", ax, ay, az);
        println!("mag   x={:.2} y={:.2} z={:.2} uT", mx, my, mz);
        std::thread::sleep(std::time::Duration::from_millis(1000));
    }
}
