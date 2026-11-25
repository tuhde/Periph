use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads1013Full, ADS1013_I2C_ADDRESS, ADS1013_DR_128, ADS1013_DR_3300};
use std::thread::sleep;
use std::time::Duration;

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).
const DIVIDER_RATIO: f32 = 2.0;
const LOG_SAMPLES: usize = 10;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads1013Full::new(dev, ADS1013_I2C_ADDRESS); // Create ADS1013 Full driver, (i2c, addr=0x48)
    let mut delay = Delay;

    // --- Slow, low-noise single-shot logging ---
    // 128 SPS gives the lowest noise; one conversion per second keeps the chip
    // in power-down in between.
    adc.set_data_rate(ADS1013_DR_128).expect("set_data_rate"); // Set data rate, (dr 0–6) → ()
    let mut low = f32::MAX;
    let mut high = f32::MIN;
    for _ in 0..LOG_SAMPLES {
        let raw = adc.read_raw(&mut delay).expect("read_raw"); // Read raw code, (delay) → i16 (−2048…2047)
        let v = raw as f32 * adc.lsb(); // Read code size, () → f32 V
        let battery = v * DIVIDER_RATIO;
        low = low.min(battery);
        high = high.max(battery);
        println!("raw {} divider {:.3} V battery {:.3} V (min {:.3}, max {:.3})", raw, v, battery, low, high);
        sleep(Duration::from_secs(1));
    }

    // --- Fast continuous averaging ---
    // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
    adc.set_data_rate(ADS1013_DR_3300).expect("set_data_rate"); // Set data rate, (dr 0–6) → ()
    adc.start_continuous().expect("start_continuous"); // Start continuous conversion, () → ()
    sleep(Duration::from_millis(10));
    let mut total = 0.0f32;
    for _ in 0..100 {
        total += adc.read_continuous().expect("read_continuous"); // Read latest voltage, () → f32 V
        sleep(Duration::from_millis(1));
    }
    adc.stop_continuous().expect("stop_continuous"); // Stop continuous conversion, () → ()
    println!("average battery {:.3} V", total / 100.0 * DIVIDER_RATIO);
}
