use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina219Full, BRNG_32V, PGA_8, ADC_12BIT, MODE_SHUNT_BUS_CONT};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    println!("voltage: {:.3} V", chip.voltage().unwrap());
    println!("shunt_voltage: {:.6} V", chip.shunt_voltage().unwrap());
    println!("current: {:.6} A", chip.current().unwrap());
    println!("power: {:.6} W", chip.power().unwrap());

    println!("conversion_ready: {}", chip.conversion_ready().unwrap());
    println!("overflow: {}", chip.overflow().unwrap());

    chip.configure(BRNG_32V, PGA_8, ADC_12BIT, ADC_12BIT, MODE_SHUNT_BUS_CONT).unwrap();

    println!("conversion_ready: {}", chip.conversion_ready().unwrap());
    println!("overflow: {}", chip.overflow().unwrap());

    chip.shutdown().unwrap();
    println!("shutdown done");

    chip.wake().unwrap();
    println!("wake done");

    chip.trigger().unwrap();
    println!("trigger done");

    chip.reset().unwrap();
    println!("reset done");
}