use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::Mpl115a2Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut baro = Mpl115a2Minimal::new(dev, 0x60).expect("init MPL115A2"); // Create MPL115A2 driver, (i2c, addr=0x60) → Result

    for _ in 0..5 {
        let p = baro.read_pressure(&mut delay).expect("read pressure");       // Read pressure, (delay) → f32 Pa
        let t = baro.read_temperature(&mut delay).expect("read temperature"); // Read temperature, (delay) → f32 C (approximate)
        println!("{:.0} Pa, {:.1} C", p, t);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
