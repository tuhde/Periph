use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4725Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4725Full::new(dev, addr).expect("init MCP4725");

    const STEP: f32 = 1.0 / 20.0;
    const DELAY_MS: u64 = 100;

    println!("MCP4725 demo: triangle wave");
    // Up sweep: 0 to full scale in 21 steps
    for n in 0..=20 {
        let fraction = n as f32 * STEP;
        dac.set_voltage(fraction).expect("set voltage");           // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
        let code = (fraction * 4095.0).round() as u16;
        let approx_v = code as f32 * 3.3 / 4096.0;
        println!("n={:2} fraction={:.2} code={:4} approx_v={:.3}V", n, fraction, code, approx_v);
        sleep(Duration::from_millis(DELAY_MS));
    }
    // Down sweep: full scale back to 0 in 20 steps
    for n in (0..=20).rev() {
        let fraction = n as f32 * STEP;
        dac.set_voltage(fraction).expect("set voltage");           // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
        let code = (fraction * 4095.0).round() as u16;
        let approx_v = code as f32 * 3.3 / 4096.0;
        println!("n={:2} fraction={:.2} code={:4} approx_v={:.3}V", n, fraction, code, approx_v);
        sleep(Duration::from_millis(DELAY_MS));
    }
    println!("Demo complete");
}