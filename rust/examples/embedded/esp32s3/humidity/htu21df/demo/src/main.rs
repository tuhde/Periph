#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HTU21DFFull;

esp_app_desc!();

const ADDR: u8 = 0x40;

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

    // Create HTU21D-F driver, (i2c, addr=0x40) → HTU21DF
    let mut chip = HTU21DFFull::new(i2c, ADDR);

    // Soft reset, (delay) → Result<()>
    chip.init(&mut delay).expect("init");
    chip.soft_reset(&mut delay).expect("soft_reset");
    // Read the user register, () → Result<u8> ; Get resolution, () → Result<u8>
    println!("user register 0x{:02X}", chip.read_user_register().expect("read_user_register"));
    println!("resolution {}", chip.get_resolution().expect("get_resolution"));

    // --- Five readings at 12/14 bit ---
    for _ in 0..5 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Compensated humidity, (delay) → Result<f32> %RH ; Compute dew point, (delay) → Result<f32> °C
        println!("{:.2} C {:.1} %RH (compensated {:.1}) dew point {:.2} C", t, rh,
            chip.read_humidity_compensated(&mut delay).expect("compensated"), chip.dew_point(&mut delay).expect("dew_point"));
        sleep_ms!(2000);
    }
    // Read raw codes, (delay) → Result<(u16, u16)>
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    // Set resolution, (res) → Result<()>
    chip.set_resolution(1).expect("set_resolution");
    let t = chip.read_temperature(&mut delay).expect("temperature");
    let rh = chip.read_humidity(&mut delay).expect("humidity");
    println!("8/12 bit: {:.2} C {:.1} %RH", t, rh);
    chip.set_resolution(0).expect("set_resolution");
    // Use hold-master commands, (on) → ()
    chip.set_hold_master(false);

    // --- Heater self-check: 10 s ---
    let rh_before = chip.read_humidity(&mut delay).expect("humidity");
    let t_before = chip.read_temperature(&mut delay).expect("temperature");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.heater().expect("heater"));
    println!("RH drop {:.1} %RH, temperature rise {:.2} K",
        rh_before - chip.read_humidity(&mut delay).expect("humidity"),
        chip.read_temperature(&mut delay).expect("temperature") - t_before);
    chip.set_heater(false).expect("heater off");
    // End-of-battery flag, () → Result<bool>
    println!("battery low {}", chip.battery_low().expect("battery_low"));

    loop {
        delay.delay_ms(1000);
    }
}
