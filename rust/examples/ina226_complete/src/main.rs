use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina226Full, BOL, BUL, SOL, SUL, POL};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina226Full::new(dev, addr, 0.1, 2.0).expect("init INA226");

    println!("manufacturer_id: 0x{:04X}", chip.manufacturer_id().unwrap());
    println!("die_id: 0x{:04X}", chip.die_id().unwrap());

    chip.configure(3, 4, 4, 7).unwrap();

    println!("voltage: {:.3} V", chip.voltage().unwrap());
    println!("shunt_voltage: {:.6} V", chip.shunt_voltage().unwrap());
    println!("current: {:.6} A", chip.current().unwrap());
    println!("power: {:.6} W", chip.power().unwrap());

    println!("conversion_ready: {}", chip.conversion_ready().unwrap());
    println!("overflow: {}", chip.overflow().unwrap());

    chip.set_alert(BOL, 5.0, false, false).unwrap();
    println!("alert_flags after BOL set: 0x{:04X}", chip.alert_flags().unwrap());

    chip.set_alert(SOL, 0.1, false, false).unwrap();
    chip.set_alert(SUL, -0.1, false, false).unwrap();
    chip.set_alert(BUL, 1.0, false, false).unwrap();
    chip.set_alert(POL, 1.0, false, false).unwrap();

    chip.shutdown().unwrap();
    println!("shutdown done");

    chip.wake().unwrap();
    println!("wake done");

    chip.reset().unwrap();
    println!("reset done");
}
