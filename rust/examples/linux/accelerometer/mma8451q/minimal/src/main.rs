use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Mma8451QMinimal;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1C);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create MMA8451Q driver, (i2c bus, i2c addr) → Result
    let mut chip = Mma8451QMinimal::new(dev, addr).expect("init MMA8451Q");

    // Wait for the STANDBY → ACTIVE turn-on time, (ms) → None
    std::thread::sleep(Duration::from_millis(22));

    loop {
        // Read 3-axis acceleration, () → (f32 x, f32 y, f32 z) g
        let (x, y, z) = chip.read().expect("read");
        println!("x={:.3} y={:.3} z={:.3} g", x, y, z);
        std::thread::sleep(Duration::from_millis(100));
    }
}
