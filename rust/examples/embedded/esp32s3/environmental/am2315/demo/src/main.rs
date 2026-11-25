#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::AM2315Full;

esp_app_desc!();

const ADDR: u8 = 0x5C;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create AM2315 driver, (i2c, ADDR=0x5C) → AM2315
    let mut chip = AM2315Full::new(i2c, ADDR);

    // Read device information, (delay) → Result<(u16, u8, u32)>
    let (model, version, device_id) = chip.read_device_info(&mut delay).expect("read_device_info");
    println!("model {}, version {}, device id 0x{:08X}", model, version, device_id);

    // --- Weather-station logger: a sample every 5 s, min/max tracking ---
    // The sensor measures after a communication and returns that result on the next one,
    // so every sample is the previous measurement; the driver waits 2 s before every
    // transaction and checks the CRC-16.
    let (mut t_min, mut t_max, mut h_min, mut h_max) = (1000.0f32, -1000.0f32, 1000.0f32, -1000.0f32);
    for i in 1..=12 {
        let (t, rh) = if i % 10 == 0 {
            // Real-time value, (delay) → Result<(f32, f32)> — two reads
            chip.read_fresh(&mut delay).expect("read_fresh")
        } else {
            // Read temperature and humidity, (delay) → Result<(f32, f32)>
            chip.read(&mut delay).expect("read")
        };
        t_min = t_min.min(t); t_max = t_max.max(t);
        h_min = h_min.min(rh); h_max = h_max.max(rh);
        // Dew point approximation (no_std has no ln(); accurate within 1 K for RH above 50 %)
        let dew = t - (100.0 - rh) / 5.0;
        println!("#{}  {:.1} C  {:.1} %RH  dew point {:.1} C  (T {:.1}..{:.1}, RH {:.1}..{:.1})", i, t, rh, dew, t_min, t_max, h_min, h_max);
        sleep_ms!(3000);
    }

    // --- User registers as a persistent counter ---
    // Write user registers, (user1, user2, delay) → Result<()> ; Read user registers, (delay) → Result<(u16, u16)>
    chip.write_user_registers(12, 0xBEEF, &mut delay).expect("write_user_registers");
    println!("user registers {:04X?}", chip.read_user_registers(&mut delay).expect("read_user_registers"));
    println!("done");

    loop {
        delay.delay_ms(1000);
    }
}
