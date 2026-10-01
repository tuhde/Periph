use linux_embedded_hal::I2cdev;
use periph::discovery::discover;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let mut dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    for d in discover(&mut dev, false).expect("discover") { // Discover chips, (i2c, active=false) → Vec<DiscoveredDevice>
        println!("0x{:02X} {:?}", d.address, d.identified.map(|i| vec![i]).unwrap_or(d.candidates));
    }
}
