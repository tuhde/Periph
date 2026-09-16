use linux_embedded_hal::I2cdev;
use periph::chips::power::Ade7953Minimal;
use embedded_hal::delay::DelayNs;
use std::time::Duration;

struct Delay;

impl DelayNs for Delay {
    fn delay_ms(&mut self, ms: u32) {
        std::thread::sleep(Duration::from_millis(ms as u64));
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut ade = Ade7953Minimal::new(dev, addr, 251.0, 30.0, &mut delay).expect("init ADE7953");  // Create ADE7953 driver, (i2c, addr, voltage_gain=V/V, current_gain=A/V, delay)

    loop {
        let v = ade.voltage().expect("voltage");                       // Read bus voltage, () → f32 V
        let i = ade.current().expect("current");                       // Read load current, () → f32 A
        let p = ade.active_power().expect("active_power");             // Read active power, () → f32 W
        let e = ade.active_energy().expect("active_energy");           // Read active energy, () → f32 Wh
        println!("V={:.2}  I={:.3}  P={:.2}  E={:.4}", v, i, p, e);
        std::thread::sleep(Duration::from_secs(1));
    }
}