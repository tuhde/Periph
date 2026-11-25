use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads1014Full, ADS1014_I2C_ADDRESS, ADS1014_PGA_1_024, ADS1014_DR_250, ADS1014_COMP_WINDOW, ADS1014_ACTIVE_LOW, ADS1014_QUEUE_2, ADS1014_SOURCE_CONVERSION_READY};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads1014Full::new(dev, ADS1014_I2C_ADDRESS); // Create ADS1014 Full driver, (i2c, addr=0x48)
                                                              // no I2C traffic; defaults ±2.048 V, 1600 SPS
    let mut delay = Delay;

    let raw = adc.read_raw(&mut delay).expect("read_raw"); // Read raw code, (delay) → i16 (−2048…2047)
                                                           // single-shot AIN0 − AIN1, polls OS until idle
    let v = adc.read_voltage(&mut delay).expect("read_voltage"); // Read voltage, (delay) → f32 V
                                                                 // raw × full-scale / 2048
    println!("raw {} voltage {:.4} V", raw, v);

    adc.set_gain(ADS1014_PGA_1_024).expect("set_gain"); // Set PGA, (pga 0–5) → ()
                                                        // ±1.024 V range, 0.5 mV per code
    println!("gain {}", adc.get_gain()); // Read PGA code, () → u8
                                         // cached PGA[2:0]
    println!("full scale {} V", adc.full_scale()); // Read full-scale range, () → f32 V
                                                   // ±FSR of the current gain
    println!("lsb {} V", adc.lsb()); // Read code size, () → f32 V
                                     // full_scale() / 2048

    adc.set_data_rate(ADS1014_DR_250).expect("set_data_rate"); // Set data rate, (dr 0–6) → ()
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

    adc.set_thresholds(-0.2, 0.5).expect("set_thresholds"); // Set both thresholds, (low V, high V) → ()
                                                            // converted with lsb(), clamped to the code range
    adc.set_low_threshold_raw(-200).expect("set_low_threshold_raw"); // Set low threshold, (raw −2048…2047) → ()
                                                                     // LO_THRESH = raw << 4
    adc.set_high_threshold_raw(500).expect("set_high_threshold_raw"); // Set high threshold, (raw −2048…2047) → ()
                                                                      // HI_THRESH = raw << 4
    println!("thresholds {:?}", adc.get_thresholds_raw().expect("get_thresholds_raw")); // Read thresholds, () → (i16, i16)
                                                                                        // sign-extended 12-bit codes

    adc.configure_comparator(ADS1014_COMP_WINDOW, ADS1014_ACTIVE_LOW, true, ADS1014_QUEUE_2).expect("configure_comparator"); // Configure comparator, (mode, polarity, latch, queue) → ()
                                                                                                                             // window mode, latching, 2 consecutive conversions
    adc.clear_alert().expect("clear_alert"); // Release latched alert, () → ()
                                             // reads CONVERSION
    adc.disable_comparator().expect("disable_comparator"); // Disable comparator, () → ()
                                                           // COMP_QUE = 11, ALERT/RDY high-impedance

    adc.enable_interrupt(ADS1014_SOURCE_CONVERSION_READY).expect("enable_interrupt"); // Route conversion-ready to ALERT/RDY, (source) → ()
                                                                                      // HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
    println!("status {}", adc.poll_interrupt(None).expect("poll_interrupt")); // Poll ALERT/RDY, (pin_level: Option<bool>) → u8 source
                                                                              // pin level when wired, else CONFIG.OS fallback
    adc.disable_interrupt(ADS1014_SOURCE_CONVERSION_READY).expect("disable_interrupt"); // Release ALERT/RDY, (source) → ()
                                                                                        // COMP_QUE = 11
    adc.reset().expect("reset"); // Reset to power-up defaults, () → ()
                                 // rewrites CONFIG and both thresholds
}
