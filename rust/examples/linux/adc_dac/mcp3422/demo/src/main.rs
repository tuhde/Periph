// Demo: a two-channel bridge / thermocouple monitor. Reads CH1 (a thermistor
// divider) at 16 bit / x1, then 18 bit / x8 on CH2 (a bridge sensor), then runs
// continuous conversion on CH1 at 12 bit / 240 SPS and finishes with a general
// call reset.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{MCP3422Full, MCP3422_I2C_ADDRESS};
use std::thread::sleep;
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = MCP3422Full::new(dev, MCP3422_I2C_ADDRESS); // Create MCP3422 Full driver, (i2c, addr=0x68)
    adc.init().expect("init");                                // Write the default configuration, () → ()

    // --- CH1: thermistor divider at 16 bit / x1 ---
    for _ in 0..3 {
        let raw = adc.read_raw(&mut Delay, 0).expect("read_raw"); // One-shot code, (delay, channel 0–1) → i32
        let v = adc.read_voltage(&mut Delay, 0).expect("read_voltage"); // One-shot voltage, (delay, channel 0–1) → f32 V
        println!("CH1 raw {} = {:.5} V", raw, v);
        sleep(Duration::from_secs(1));
    }

    // --- CH2: bridge sensor at 18 bit / x8 ---
    adc.set_resolution(18).expect("resolution"); // Select 18 bit / 3.75 SPS, (bits) → Result
    adc.set_gain(8).expect("gain"); // Select PGA x8, (gain) → Result
    let mut bridge = [0.0f32; 1];
    adc.read_scan(&mut Delay, &[1], &mut bridge).expect("read_scan"); // Scan channels, (delay, channels, out) → ()
    println!("CH2 {:.1} uV, LSB {:.3} uV", bridge[0] * 1e6, adc.lsb() / adc.get_gain() as f32 * 1e6); // LSB, () → f32 V

    // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
    adc.set_resolution(12).expect("resolution");
    adc.set_gain(1).expect("gain");
    adc.start_continuous(Some(0)).expect("start_continuous"); // Start continuous conversion, (channel Option<u8>) → ()
    let t0 = Instant::now();
    let mut samples = [0.0f32; 20];
    for s in samples.iter_mut() {
        *s = adc.read_continuous(&mut Delay).expect("read_continuous"); // Next fresh voltage, (delay) → f32 V
    }
    let elapsed = t0.elapsed().as_secs_f32();
    adc.stop_continuous().expect("stop_continuous"); // Back to one-shot / standby, () → ()
    let min = samples.iter().cloned().fold(f32::MAX, f32::min);
    let max = samples.iter().cloned().fold(f32::MIN, f32::max);
    let mean = samples.iter().sum::<f32>() / samples.len() as f32;
    println!("min {:.5} max {:.5} mean {:.5} V, {:.0} samples/s", min, max, mean, samples.len() as f32 / elapsed);

    adc.general_call_reset().expect("general_call_reset"); // Reset to power-on defaults, () → ()
}
