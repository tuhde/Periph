use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina226Full, BOL, BUL};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina226Full::new(dev, addr, 0.1, 2.0).expect("init INA226");

    // Average 16 samples to smooth out noise on a noisy power rail
    chip.configure(3, 4, 4, 7).unwrap();

    // Alert if bus voltage goes outside 4.5–5.5 V (expected 5 V USB supply)
    chip.set_alert(BOL, 5.5, false, false).unwrap();
    chip.set_alert(BUL, 4.5, false, false).unwrap();

    // Sample every second; a real application would sleep between conversions
    for _ in 0..10 {
        while !chip.conversion_ready().unwrap() {
            sleep(Duration::from_millis(10));
        }

        let v = chip.voltage().unwrap();
        let i = chip.current().unwrap();
        let p = chip.power().unwrap();
        println!("V={:.3}V  I={:.4}A  P={:.4}W", v, i, p);

        if chip.overflow().unwrap() {
            eprintln!("WARNING: math overflow — increase max_current or reduce shunt");
        }

        sleep(Duration::from_secs(1));
    }
}
