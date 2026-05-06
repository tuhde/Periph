use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina219Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    let mut readings: Vec<(f32, f32, f32)> = Vec::new();
    for n in 0..10 {
        let v = chip.voltage().unwrap();
        let i = chip.current().unwrap();
        let p = chip.power().unwrap();
        readings.push((v, i, p));
        println!("V={:.3}V  I={:.4}A  P={:.4}W", v, i, p);
        if n == 3 {
            println!("--- switch on load now ---");
        }
        sleep(Duration::from_secs(1));
    }

    let v_vals: Vec<f32> = readings.iter().map(|r| r.0).collect();
    let i_vals: Vec<f32> = readings.iter().map(|r| r.1).collect();
    let p_vals: Vec<f32> = readings.iter().map(|r| r.2).collect();

    println!("V min={:.4} max={:.4} mean={:.4}", v_vals.iter().cloned().fold(f32::INFINITY, f32::min), v_vals.iter().cloned().fold(f32::NEG_INFINITY, f32::max), v_vals.iter().sum::<f32>() / v_vals.len() as f32);
    println!("I min={:.4} max={:.4} mean={:.4}", i_vals.iter().cloned().fold(f32::INFINITY, f32::min), i_vals.iter().cloned().fold(f32::NEG_INFINITY, f32::max), i_vals.iter().sum::<f32>() / i_vals.len() as f32);
    println!("P min={:.4} max={:.4} mean={:.4}", p_vals.iter().cloned().fold(f32::INFINITY, f32::min), p_vals.iter().cloned().fold(f32::NEG_INFINITY, f32::max), p_vals.iter().sum::<f32>() / p_vals.len() as f32);
}
