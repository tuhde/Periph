use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Pcf8591Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x48);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Pcf8591Minimal::new(dev, addr).expect("init PCF8591");

    loop {
        let ch0 = adc.read_channel(0).expect("read ch0");           // Read single channel, (channel=0–3) → Result<u8, E>
        let raw = adc.read_all().expect("read all");               // Read all four channels, () → Result<[u8; 4], E>
        println!("PCF8591 minimal running ch0={} all={:?}", ch0, raw);
        sleep(Duration::from_secs(1));
    }
}
