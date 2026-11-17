#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Lc709203fFull;

esp_app_desc!();

const ADDR: u8 = 0x0B;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Lc709203fFull::new(i2c, ADDR, 1000, 1, &mut delay).expect("init LC709203F"); // Create LC709203F driver, (i2c, addr, capacity_mah, profile, delay)

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
        delay.delay_ms(5000);
    }

    chip.sleep().unwrap();
    chip.wake(&mut delay);
    println!("sleeping: {}", chip.is_sleeping(&mut delay).unwrap());

    loop {
        delay.delay_ms(1000);
    }
}
