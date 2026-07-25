use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::environmental::Aht21Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Aht21Minimal::new(dev, addr, &mut delay).expect("init AHT21");  // Create AHT21 driver, (i2c, addr=0x38, delay) → Result

    loop {
        let (t, h) = chip.read(&mut delay).expect("read");                         // Trigger measurement, (delay) → (f32 °C, f32 %RH)
        println!("T={:.2} C  H={:.2} %RH", t, h);
        sleep(Duration::from_secs(1));
    }
}
