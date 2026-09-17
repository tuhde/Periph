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

    // --- Configure for electronic compass ---
    // 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
    chip.configure(15.0, 8, 1).expect("configure");  // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None

    println!("Electronic compass demo — hold sensor flat, rotate horizontally");
    println!("Vertical mount warning: |Z| > 30 µT indicates tilt compensation needed");
    println!();

    // --- Sample and compute heading ---
    // User rotates the sensor horizontally; we compute heading from X/Y axes.
    // At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
    for n in 0..10 {
        while !chip.data_ready().expect("data_ready") {  // Check data ready, () → bool
            sleep(Duration::from_millis(1));
        }
        let (x, y, z) = chip.magnetic_field().expect("magnetic_field");  // Read magnetic field, () → (Option<f32>, Option<f32>, Option<f32>) T

        // --- Compute heading from X and Y ---
        if let (Some(x), Some(y)) = (x, y) {
            let heading = (y as f64).atan2(x as f64).to_degrees();
            let heading = if heading < 0.0 { heading + 360.0 } else { heading };
            println!("Heading: {:.1}°", heading);
        }

        // --- Vertical mount detection ---
        if let Some(z) = z {
            if z.abs() > 30e-6 {
                println!("[TILT WARNING] Z={:.1} µT — tilt compensation needed", z * 1e6);
            }
        }

        if n == 4 {
            println!(">>> Now tilt sensor vertically <<<");
        }

        sleep(Duration::from_millis(500));
    }
}