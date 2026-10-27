use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::Lsm303Minimal;
use periph::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create LSM303 driver, (connection, accel_addr=0x19, mag_addr=0x1E, delay) → Result
    let mut chip = Lsm303Minimal::new(Connection::new(dev), 0x19, 0x1E, Delay).expect("init LSM303");

    loop {
        // Read 3-axis acceleration, () → (f32, f32, f32) g
        let (ax, ay, az) = chip.read_acceleration().expect("read acceleration");
        // Read 3-axis magnetic field, () → (f32, f32, f32) µT
        let (mx, my, mz) = chip.read_magnetic().expect("read magnetic");
        println!("a=({:.3}, {:.3}, {:.3}) g  m=({:.1}, {:.1}, {:.1}) µT",
                 ax, ay, az, mx, my, mz);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}
