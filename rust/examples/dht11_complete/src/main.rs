use linux_embedded_hal::I2cdev;
use periph::chips::humidity::Dht11Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dht = Dht11Full::new(dev, addr);

    loop {
        let temp = dht.read_temperature().unwrap_or(f32::NAN);
        let hum = dht.read_humidity().unwrap_or(f32::NAN);
        println!("Temperature: {:.1} C, Humidity: {:.1} %RH", temp, hum);

        std::thread::sleep(std::time::Duration::from_secs(2));
    }
}
