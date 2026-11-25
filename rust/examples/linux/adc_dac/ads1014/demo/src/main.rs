use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads1014Full, ADS1014_I2C_ADDRESS, ADS1014_PGA_0_256, ADS1014_DR_250, ADS1014_COMP_TRADITIONAL, ADS1014_ACTIVE_LOW, ADS1014_QUEUE_4, ADS1014_SOURCE_COMPARATOR};
use std::thread::sleep;
use std::time::Duration;

// Shunt-current monitor: a 0.1 ohm shunt in the load supply line is read on
// the ±0.256 V range; the traditional comparator watches for more than 1 A.
const SHUNT_OHMS: f32 = 0.1;
const SAMPLES: usize = 20;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads1014Full::new(dev, ADS1014_I2C_ADDRESS); // Create ADS1014 Full driver, (i2c, addr=0x48)
    let mut delay = Delay;

    // --- Pick the range and speed for a small shunt voltage ---
    // ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
    // for a once-per-second report.
    adc.set_gain(ADS1014_PGA_0_256).expect("set_gain"); // Set PGA, (pga 0–5) → ()
    adc.set_data_rate(ADS1014_DR_250).expect("set_data_rate"); // Set data rate, (dr 0–6) → ()

    // --- Arm the over-current comparator ---
    // 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
    // (clears again below 0.08 V), latched so a brief spike is not missed, and
    // 4 consecutive conversions are needed to assert.
    adc.set_thresholds(0.08, 0.1).expect("set_thresholds"); // Set both thresholds, (low V, high V) → ()
    adc.configure_comparator(ADS1014_COMP_TRADITIONAL, ADS1014_ACTIVE_LOW, true, ADS1014_QUEUE_4).expect("configure_comparator"); // Configure comparator, (mode, polarity, latch, queue) → ()
    adc.enable_interrupt(ADS1014_SOURCE_COMPARATOR).expect("enable_interrupt"); // Route comparator to ALERT/RDY, (source) → ()

    // --- Monitor the current ---
    // The pin level is not wired in this demo, so the alert state is inferred
    // from the measured current; clear_alert() releases the latch afterwards.
    for _ in 0..SAMPLES {
        let v = adc.read_voltage(&mut delay).expect("read_voltage"); // Read voltage, (delay) → f32 V
        let amps = v / SHUNT_OHMS;
        println!("{:.3} V -> {:.2} A", v, amps);
        if amps > 1.0 {
            println!("over-current!");
            adc.clear_alert().expect("clear_alert"); // Release latched alert, () → ()
        }
        sleep(Duration::from_secs(1));
    }

    // --- Release the alert pin when finished ---
    adc.disable_interrupt(ADS1014_SOURCE_COMPARATOR).expect("disable_interrupt"); // Release ALERT/RDY, (source) → ()
}
