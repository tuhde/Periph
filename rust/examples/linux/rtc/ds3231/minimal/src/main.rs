use linux_embedded_hal::I2cdev;
use periph::chips::rtc::{Ds3231Minimal, DateTime};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Ds3231Minimal::new(dev, 0x68).expect("init DS3231"); // Create DS3231 driver, (i2c, addr=0x68)

    rtc.set_datetime(DateTime {
        year: 2026, month: 9, day: 22, weekday: 2, hour: 14, minute: 30, second: 0,
    }).expect("set_datetime"); // Set the calendar clock, (datetime) → ()

    for _ in 0..10 {
        let dt = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
        let temp = rtc.read_temperature().expect("read_temperature"); // Read temperature, () → f32 C
        println!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}  {:.2} C",
                 dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, temp);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
