use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::power::Lc709203fFull;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x0B);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Lc709203fFull::new(dev, addr, 1000, 1, &mut delay).expect("init LC709203F"); // Create LC709203F driver, (i2c, addr, capacity_mah, profile, delay)

    println!("IC version {:#06x}, profile id {:#06x}, RSOC {} %", chip.ic_version().unwrap(), chip.profile_id().unwrap(), chip.rsoc().unwrap());
    chip.set_alarm_rsoc(20).unwrap();                                   // Alarm at 20 % RSOC, (percent)
    chip.set_alarm_voltage(3.5).unwrap();                               // Alarm at 3.5 V, (volts)

    // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
    for _ in 0..12 {
        chip.set_temperature(25.0).unwrap();
        let v = chip.voltage().unwrap();
        let p = chip.percent().unwrap();
        let t = chip.temperature().unwrap();
        println!("{:.3} V  {:.1} %  {:.1} C  alarm={}", v, p, t, chip.poll_interrupt().unwrap());
        sleep(Duration::from_millis(5000));
    }

    chip.sleep().unwrap();
    chip.wake(&mut delay);
    println!("sleeping: {}", chip.is_sleeping(&mut delay).unwrap());
}
