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

    // Soft reset, (delay) → Result<()>; 15 ms power-up wait, soft reset, 15 ms
    chip.init(&mut delay).expect("init");
    // Read temperature and humidity, (delay) → Result<(f32, f32)> °C, %RH; no hold master, CRC checked
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32> %RH; not clamped
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read temperature only, (delay) → Result<f32> °C
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Temperature-compensated humidity, (delay) → Result<f32> %RH; -0.15 %RH/°C relative to 25 °C
    println!("compensated humidity {:.1} %RH", chip.read_humidity_compensated(&mut delay).expect("compensated"));
    // Read raw codes, (delay) → Result<(u16, u16)>; (temperature, humidity), status bits cleared
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));

    // Set resolution, (res) → Result<()>; 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
    chip.set_resolution(3).expect("set_resolution");
    // Get resolution, () → Result<u8>
    println!("resolution {}", chip.get_resolution().expect("get_resolution"));
    chip.set_resolution(0).expect("set_resolution");

    // Switch the heater, (on) → Result<()>; about 5.5 mW, readings drift while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.heater().expect("heater"));
    chip.set_heater(false).expect("heater off");
    // End-of-battery flag, () → Result<bool>; true when VDD is below about 2.25 V
    println!("battery low {}", chip.battery_low().expect("battery_low"));
    // Read the user register, () → Result<u8>; 0x02 after reset
    println!("user register 0x{:02X}", chip.read_user_register().expect("read_user_register"));
    // Use hold-master commands, (on) → (); needs bus-layer clock stretching; off polls the NACKed read header
    chip.set_hold_master(false);
    // Compute dew point, (delay) → Result<f32> °C; one measurement
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
    // Soft reset, (delay) → Result<()>; 0xFE, waits 15 ms; the heater bit survives
    chip.soft_reset(&mut delay).expect("soft_reset");

    loop {
        delay.delay_ms(1000);
    }
}
