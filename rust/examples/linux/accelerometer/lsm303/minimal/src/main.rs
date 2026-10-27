use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::Lsm303Minimal;
use periph::connection::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);                                          // Create bus connection, (i2cdev) → Connection
    let mut delay = Delay;
    let mut chip = Lsm303Minimal::new(conn, &mut delay).expect("init LSM303"); // Create LSM303 driver, (connection, delay) → Result

    loop {
        let (x, y, z) = chip.read_acceleration().expect("read_acceleration");       // Read acceleration, () → (f32, f32, f32) g
        let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");            // Read magnetic flux density, () → (f32, f32, f32) µT
        println!("a=({:.3}, {:.3}, {:.3}) g  m=({:.1}, {:.1}, {:.1}) uT", x, y, z, mx, my, mz);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}
