#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::hs40xx::{AlertThresholds, HS40xxFull, SOURCE_RH_HIGH};

esp_app_desc!();

const ADDR: u8 = 0x54;

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

    // Create HS40xx driver, (i2c, ADDR=0x54) → HS40xx
    let mut chip = HS40xxFull::new(i2c, ADDR);

    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    // Read the 32-bit sensor ID, (delay) → Result<u32>
    println!("ID {:#x}", chip.read_id(&mut delay).expect("read_id"));
    for _ in 0..5 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C, {:.1} %RH", t, rh);
        sleep_ms!(2000);
    }
    // Set alert thresholds, (&AlertThresholds) → Result<()>
    chip.set_alert_thresholds(&AlertThresholds { rh_high: Some(60.0), rh_high_reset: Some(55.0), ..Default::default() }).expect("set_alert_thresholds");
    // Enable alert sources, (source, delay) → Result<()>
    chip.enable_interrupt(SOURCE_RH_HIGH, &mut delay).expect("enable_interrupt");
    // Start periodic mode, (period_s, delay) → Result<()>
    chip.start_periodic(1.0, &mut delay).expect("start_periodic");
    for _ in 0..15 {
        sleep_ms!(1000);
        // Fetch the latest periodic result, (delay) → Result<Periodic>
        let p = chip.read_periodic(&mut delay).expect("read_periodic");
        let alert = if p.status & SOURCE_RH_HIGH != 0 { "ALERT" } else { "ok" };
        println!("{:.2} C, {:.1} %RH  {}", p.temperature_c, p.humidity_pct, alert);
    }
    // Stop periodic mode, () → Result<()>
    chip.stop_periodic().expect("stop_periodic");
    // Soft reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");

    loop {
        delay.delay_ms(1000);
    }
}
