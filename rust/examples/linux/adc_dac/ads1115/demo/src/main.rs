//! Demo for the ADS1115 — a real-world scenario (see the section comments below).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::ads1115::{ADS1115_ACTIVE_LOW, ADS1115_COMP_WINDOW, ADS1115_DIFF_2_3, ADS1115_DR_250, ADS1115_I2C_ADDRESS, ADS1115_MUX_AIN0_GND, ADS1115_PGA_0_256, ADS1115_PGA_4_096, ADS1115_QUEUE_2};
use periph::chips::adc_dac::Ads1115Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
let mut adc = Ads1115Full::new(dev, ADS1115_I2C_ADDRESS);                                       // Create ADS1115 Full driver, (i2c, addr=0x48)

    // --- Configure the converter ---
    // ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
    // reads short while still averaging out most noise.
    adc.set_gain(ADS1115_PGA_4_096).expect("set_gain");                                         // Set gain, (pga 0–5) → Result<(), E>
    adc.set_data_rate(ADS1115_DR_250).expect("set_data_rate");                                  // Set data rate, (dr 0–7) → Result<(), E>

    // --- Read the inputs ---
    // Single-shot mode powers the chip down between conversions.
    for _ in 0..3 {
        for ch in 0..4u8 {
            let raw = adc.read_raw(ch, &mut delay).expect("read_raw");                          // Read raw code, (channel=0, delay) → Result<i16, E>
            println!("AIN{} raw {} {:.5} V", ch, raw, raw as f32 * adc.lsb());
        }
        sleep(Duration::from_millis(1000));
    }

    // --- Arm a window comparator ---
    // ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
    // consecutive conversions; the latch holds it until clear_alert().
    adc.set_thresholds(0.5, 3.0).expect("set_thresholds");                                      // Set thresholds, (low V, high V) → Result<(), E>
    adc.configure_comparator(ADS1115_COMP_WINDOW, ADS1115_ACTIVE_LOW, true, ADS1115_QUEUE_2).expect("configure_comparator");// Configure comparator, (mode, polarity, latch, queue) → Result<(), E>
    adc.start_continuous(ADS1115_MUX_AIN0_GND).expect("start_continuous");                      // Start continuous, (mux 0–7) → Result<(), E>

    // --- Watch for out-of-range events ---
    // Rust drivers are polled: pass the sampled ALERT/RDY level, or None to
    // fall back to CONFIG.OS (which cannot see a comparator alert).
    for _ in 0..100 {
        let status = adc.poll_interrupt(None).expect("poll_interrupt");                         // Poll interrupt, (alert_pin_high: Option<bool>) → Result<u8, E>
        if status != 0 {
            println!("OUT OF RANGE");
            adc.clear_alert().expect("clear_alert");                                            // Clear alert, () → Result<(), E>
        }
        sleep(Duration::from_millis(100));
    }
    adc.disable_comparator().expect("disable_comparator");                                      // Disable comparator, () → Result<(), E>
    adc.stop_continuous().expect("stop_continuous");                                            // Stop continuous, () → Result<(), E>

    // --- Bridge-sensor style differential read ---
    // The smallest range gives 7.8 uV per code for millivolt-level signals.
    adc.set_gain(ADS1115_PGA_0_256).expect("set_gain");                                         // Set gain, (pga 0–5) → Result<(), E>
    let d = adc.read_differential(ADS1115_DIFF_2_3, &mut delay).expect("read_differential");    // Read differential, (pair 0–3, delay) → Result<f32 V, E>
    println!("differential {:.6} V", d);
}
