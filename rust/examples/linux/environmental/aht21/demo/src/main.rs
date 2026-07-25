use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::environmental::Aht21Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Aht21Full::new(dev, addr, &mut delay).expect("init AHT21");      // Create AHT21 driver, (i2c, addr=0x38, delay) → Result

    // --- Verify calibration before starting the logging session ---
    // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during new().
    println!("Calibrated: {}", chip.is_calibrated().unwrap());                      // Check calibration status, () → Result<bool>

    println!("{:<8} {:<10} {:<10} {:<10}", "Time", "T (C)", "RH (%)", "Dew (C)");
    for n in 0..60 {
        // --- Each reading requires an 80 ms measurement cycle ---
        // The sensor cannot output data faster than this; the driver
        // handles the trigger + wait internally.
        let (t, h, crc_ok) = chip.read_with_crc(&mut delay).unwrap();               // Read with CRC verification, (delay) → Result<(f32 °C, f32 %RH, bool)>
        if !crc_ok {
            eprintln!("CRC error at sample {}", n);
            sleep(Duration::from_secs(5));
            continue;
        }

        // --- Magnus formula dew-point approximation ---
        // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
        // dew_point = (243.04 * gamma) / (17.625 - gamma)
        // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
        let gamma = (h / 100.0).ln() + (17.625 * t) / (243.04 + t);
        let dew = (243.04 * gamma) / (17.625 - gamma);

        println!("{:<8} {:<10.2} {:<10.2} {:<10.2}", n, t, h, dew);
        sleep(Duration::from_secs(5));
    }
}
