#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::{Bme680Full, OSRS_X2, OSRS_X16, OSRS_X1, MODE_FORCED, FILTER_15};

esp_app_desc!();

const ADDR: u8 = 0x76;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
    // Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
    // At tick 30, the user is prompted to expose the sensor to a VOC source
    // (isopropyl alcohol, marker pen). Gas resistance drops sharply on exposure
    // and recovers over the remaining ticks, demonstrating raw VOC sensitivity
    // without the closed-source BSEC library.
    let mut bme = Bme680Full::new(i2c, ADDR).expect("init BME680"); // Create BME680 driver, (i2c, ADDR=0x76)
    bme.configure(OSRS_X2, OSRS_X16, OSRS_X1, MODE_FORCED, FILTER_15).expect("configure"); // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → ()
    bme.set_heater(320, 150).expect("set_heater");                 // Configure heater profile 0, (temp_c=320, duration_ms=150) → ()

    let mut t_min = f32::MAX;
    let mut t_max = f32::MIN;
    let mut t_sum = 0.0f32;
    let mut g_min = f32::MAX;
    let mut g_max = 0.0f32;
    let mut gas_count = 0u32;

    for n in 0..60u32 {
        if n == 30 {
            println!("--- Expose sensor to VOC source now (alcohol/marker) ---");
        }
        let (t, p, h, g) = bme.read_all().expect("read_all");      // Read all sensors in one cycle, () → (f32, f32, f32, f32)
        if t < t_min { t_min = t; }
        if t > t_max { t_max = t; }
        t_sum += t;
        if !g.is_nan() {
            if g < g_min { g_min = g; }
            if g > g_max { g_max = g; }
            gas_count += 1;
        }
        println!("{}: {:.1} C, {:.1} %RH, {:.1} hPa, {:.0} Ohm", n, t, h, p, g);
        delay.delay_ms(5000);
    }

    let t_avg = t_sum / 60.0;
    println!("T: {:.1}/{:.1}/{:.1} C", t_min, t_avg, t_max);
    if gas_count > 0 && g_min > 0.0 {
        println!("VOC response ratio: {:.1}x", g_max / g_min);
    }
    loop {}
}
