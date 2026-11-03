use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Lsm303Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Minimal::new(dev).expect("init LSM303");

    loop {
        let (ax, ay, az) = chip.read_acceleration().expect("read acceleration");
        let (mx, my, mz) = chip.read_magnetic().expect("read magnetic");
        println!("accel x={:.4} y={:.4} z={:.4} g", ax, ay, az);
        println!("mag   x={:.2} y={:.2} z={:.2} uT", mx, my, mz);
        std::thread::sleep(std::time::Duration::from_millis(1000));
    }
}
