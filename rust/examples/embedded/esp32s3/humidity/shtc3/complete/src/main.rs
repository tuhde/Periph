//! Complete example for the SHTC3 — every SHTC3Full method once. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::SHTC3Full;

esp_app_desc!();

const ADDR: u8 = 0x70;

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

    // Create SHTC3 driver, (i2c, addr=0x70) → SHTC3
    let mut chip = SHTC3Full::new(i2c, ADDR);
    // Soft reset and ID check, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>; normal mode, both CRCs verified, one retry
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32 %RH>
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("rh"));
    // Read temperature only, (delay) → Result<f32 °C>
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("t"));
    // Read raw codes, (delay) → Result<(u16, u16)>; (raw_temperature, raw_humidity)
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));
    // Read the ID word, (delay) → Result<u16>; (id & 0x083F) == 0x0807 for the SHTC3
    println!("id 0x{:04X}", chip.read_id(&mut delay).expect("id"));

    // Select low-power mode, (on) → ()
    // 0.7 ms measurement, slightly noisier
    chip.set_low_power(true);
    println!("low power {:?}", chip.read(&mut delay).expect("read"));
    chip.set_low_power(false);

    // Select clock stretching, (on) → (); needs a controller that tolerates SCL held low
    chip.set_clock_stretching(true);
    println!("stretching {:?}", chip.read(&mut delay).expect("read"));
    chip.set_clock_stretching(false);

    // Sleep after every read?, (on) → (); off keeps the sensor idle (higher supply current)
    chip.set_sleep_between(false);
    println!("no sleep {:?}", chip.read(&mut delay).expect("read"));
    chip.set_sleep_between(true);
    // Wake-up command, (delay) → Result<()>
    chip.wake(&mut delay).expect("wake");
    // Sleep command, () → Result<()>
    chip.sleep().expect("sleep");
    // Compute dew point, (delay) → Result<f32 °C>; one measurement, Magnus formula
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew"));
    // Soft reset, (delay) → Result<()>; 0x805D, leaves the sensor idle
    chip.soft_reset(&mut delay).expect("reset");

    loop {
        sleep_ms!(1000);
    }
}
