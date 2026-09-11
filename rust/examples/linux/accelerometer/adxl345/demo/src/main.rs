use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Adxl345Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x53);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut accel = Adxl345Minimal::new(dev, addr, false).expect("init ADXL345"); // Create ADXL345 driver, (i2c, addr=0x53, spi=false)

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    const SAMPLES: usize = 50;
    const PERIOD_MS: u64 = 100;

    let mut mag_min = f32::INFINITY;
    let mut mag_max = f32::NEG_INFINITY;

    for n in 0..SAMPLES {
        let (x, y, z) = accel.read().expect("read acceleration");   // Read 3-axis acceleration, () → (f32, f32, f32) g
        let mag = (x * x + y * y + z * z).sqrt();
        if mag < mag_min { mag_min = mag; }
        if mag > mag_max { mag_max = mag; }
        println!("{:2}  x={:+.3}  y={:+.3}  z={:+.3}  |a|={:.3} g",
                 n, x, y, z, mag);
        std::thread::sleep(std::time::Duration::from_millis(PERIOD_MS));
    }

    println!("min |a|={:.3} g  max |a|={:.3} g", mag_min, mag_max);
}