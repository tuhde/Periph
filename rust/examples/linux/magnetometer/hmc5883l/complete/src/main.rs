use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::Hmc5883lFull;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Hmc5883lFull::new(dev, addr).expect("init HMC5883L");

    // --- Identification ---
    let (id_a, id_b, id_c) = chip.identify().expect("identify");  // Read ID registers, () → (u8, u8, u8)
    println!("ID: 0x{:02X} 0x{:02X} 0x{:02X}", id_a, id_b, id_c);

    // --- Status ---
    println!("Status: 0x{:02X}", chip.status().expect("status"));  // Read raw status, () → u8
    println!("Data ready: {}", chip.data_ready().expect("data_ready"));  // Check data ready, () → bool

    // --- Magnetic field readings ---
    let (x, y, z) = chip.magnetic_field().expect("magnetic_field");  // Read magnetic field, () → (Option<f32>, Option<f32>, Option<f32>) T
    println!("X={:.6} T  Y={:.6} T  Z={:.6} T", x.unwrap_or(f32::NAN), y.unwrap_or(f32::NAN), z.unwrap_or(f32::NAN));

    // --- Configuration ---
    chip.configure(15.0, 8, 1).expect("configure");  // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None
    chip.set_gain(2).expect("set_gain");              // Set gain, (gain 0-7) → None
    chip.set_mode(1).expect("set_mode single");       // Set operating mode, (0=continuous, 1=single, 2=idle) → None

    // --- Single-shot measurement ---
    sleep(Duration::from_millis(6));
    let (x, y, z) = chip.single_measurement().expect("single_measurement");  // Single-shot measurement, () → (Option<f32>, Option<f32>, Option<f32>) T
    println!("Single: X={:.6} T  Y={:.6} T  Z={:.6} T", x.unwrap_or(f32::NAN), y.unwrap_or(f32::NAN), z.unwrap_or(f32::NAN));

    chip.set_mode(0).expect("set_mode continuous");   // Set operating mode, (0=continuous, 1=single, 2=idle) → None

    // --- Self-test ---
    let (x, y, z) = chip.self_test(true).expect("self_test");  // Self-test with positive bias, (positive=bool) → (Option<f32>, Option<f32>, Option<f32>) T
    println!("Self-test: X={:.6} T  Y={:.6} T  Z={:.6} T", x.unwrap_or(f32::NAN), y.unwrap_or(f32::NAN), z.unwrap_or(f32::NAN));
}