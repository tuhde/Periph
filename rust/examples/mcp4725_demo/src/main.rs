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
    let mut dac = Mcp4725Full::new(dev, addr);

    let step = 1.0 / 20.0;
    let vd = 3.3;

    loop {
        for i in 0..=20 {
            let fraction = i as f32 * step;
            dac.set_voltage(fraction).unwrap();
            println!("{:.2} -> {:.3} V", fraction, fraction * vd);
            sleep(Duration::from_millis(100));
        }
        for i in (0..=20).rev() {
            let fraction = i as f32 * step;
            dac.set_voltage(fraction).unwrap();
            println!("{:.2} -> {:.3} V", fraction, fraction * vd);
            sleep(Duration::from_millis(100));
        }
    }
}