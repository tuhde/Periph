use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Bma180Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma180Minimal::new(dev, addr).expect("init BMA180");

    loop {
        let (x, y, z) = chip.read().expect("read");
        println!("x={:.3} y={:.3} z={:.3} g", x, y, z);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}