use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads1013Full, ADS1013_I2C_ADDRESS, ADS1013_DR_250};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads1013Full::new(dev, ADS1013_I2C_ADDRESS); // Create ADS1013 Full driver, (i2c, addr=0x48)
                                                              // no I2C traffic; fixed ±2.048 V, 1600 SPS
    let mut delay = Delay;

    let raw = adc.read_raw(&mut delay).expect("read_raw"); // Read raw code, (delay) → i16 (−2048…2047)
                                                           // single-shot AIN0 − AIN1, polls OS until idle
    let v = adc.read_voltage(&mut delay).expect("read_voltage"); // Read voltage, (delay) → f32 V
                                                                 // raw × 0.001
    println!("raw {} voltage {:.4} V", raw, v);
    println!("full scale {} V", adc.full_scale()); // Read full-scale range, () → f32 V
                                                   // always 2.048
    println!("lsb {} V", adc.lsb()); // Read code size, () → f32 V
                                     // always 0.001

    adc.set_data_rate(ADS1013_DR_250).expect("set_data_rate"); // Set data rate, (dr 0–6) → ()
                                                               // DR_250 = 250 SPS
    println!("dr code {}", adc.get_data_rate()); // Read data-rate code, () → u8
                                                 // cached DR[2:0]
    println!("sps {}", adc.data_rate_sps()); // Read data rate, () → u16 SPS
                                             // table lookup of the DR code

    adc.start_continuous().expect("start_continuous"); // Start continuous conversion, () → ()
                                                       // MODE = 0 at the current data rate
    sleep(Duration::from_millis(20));
    println!("continuous raw {}", adc.read_continuous_raw().expect("read_continuous_raw")); // Read latest code, () → i16
                                                                                            // reads CONVERSION without touching Config
    println!("continuous {:.4} V", adc.read_continuous().expect("read_continuous")); // Read latest voltage, () → f32 V
                                                                                     // raw × lsb()
    adc.stop_continuous().expect("stop_continuous"); // Stop continuous conversion, () → ()
                                                     // MODE = 1, back to single-shot
    println!("converting {}", adc.is_converting().expect("is_converting")); // Check conversion in progress, () → bool
                                                                            // CONFIG.OS = 0 (single-shot only)
    println!("config 0x{:04X}", adc.read_config().expect("read_config")); // Read CONFIG register, () → u16
                                                                          // raw 16-bit register
    adc.reset().expect("reset"); // Reset to power-up defaults, () → ()
                                 // rewrites CONFIG = 0x8583
}
