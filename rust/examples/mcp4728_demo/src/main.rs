use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4728Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4728Full::new(dev, addr).expect("init MCP4728");

    const VDD: f32 = 3.3;
    const STEP: f32 = 1.0 / 16.0;
    const DELAY_MS: u64 = 50;

    println!("MCP4728 demo: four-point calibration and synchronous staircase");

    // --- Apply four-point calibration voltages to channels A–D ---
    // A 4-channel DAC is the canonical way to bias a 4-point sensor bridge
    // (load cell, RTD conditioning, strain gauge). Each channel gets a
    // different fraction of full scale to demonstrate independent outputs.
    // Voltages printed below assume a 3.3 V supply.
    let calibration: [f32; 4] = [0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0];
    dac.set_all(calibration).expect("set all calibration");  // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
    for ch in 0..4 {
        let code = (calibration[ch] * 4095.0).round() as u16;
        let approx_v = code as f32 * VDD / 4096.0;
        println!("ch={} fraction={:.4} code={:4} approx_v={:.3}V",
                 ch, calibration[ch], code, approx_v);
    }
    sleep(Duration::from_millis(500));

    // --- Synchronous staircase from 0 to full scale on all four channels ---
    // Using set_all with the same fraction across channels keeps them in lock-step
    // and demonstrates simultaneous V_OUT update via Fast Write. A 50 ms pause
    // between steps lets the host controller observe each level on the scope.
    for n in 0..=16u32 {
        let f = n as f32 * STEP;
        dac.set_all([f, f, f, f]).expect("set all step");   // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
        let code = (f * 4095.0).round() as u16;
        let approx_v = code as f32 * VDD / 4096.0;
        println!("step={:2} fraction={:.4} code={:4} approx_v={:.3}V",
                 n, f, code, approx_v);
        sleep(Duration::from_millis(DELAY_MS));
    }

    // --- Reset all channels to 0 V before exit ---
    dac.set_all([0.0, 0.0, 0.0, 0.0]).expect("set all zero");  // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
    println!("Demo complete");
}
