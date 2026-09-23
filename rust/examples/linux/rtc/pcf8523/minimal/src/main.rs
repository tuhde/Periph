use linux_embedded_hal::I2cdev;
use periph::chips::rtc::Pcf8523Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Pcf8523Minimal::new(dev, 0x68).expect("init PCF8523"); // Create PCF8523 driver, (i2c, addr=0x68)

    for _ in 0..10 {
        let dt = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
        println!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}",
                 dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
