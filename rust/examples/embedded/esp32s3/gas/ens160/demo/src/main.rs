#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gas::Ens160Full;

esp_app_desc!();

const ADDR: u8 = 0x53;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut sensor = Ens160Full::new(i2c, ADDR).expect("init ENS160"); // Create ENS160 driver, (i2c, ADDR=0x52)

    // --- Wait for sensor warm-up ---
    // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
    // reaches 0. During warm-up, readings are unreliable. The driver surfaces the
    // status so the application can display progress to the user.
    println!("Waiting for sensor warm-up...");
    loop {                                               // Wait for valid data, () → blocks until warm
        let status = sensor.wait_for_new_data(2000).unwrap();
        let validity = (status >> 2) & 0x03;
        if validity == 0 { break; }
        if validity == 1 { println!("Warm-up in progress..."); }
        else if validity == 2 { println!("Initial start-up (first power-on, up to 1 hour)..."); }
        else { println!("No valid output"); }
    }
    println!("Sensor ready!");

    // --- Set compensation from external sensor ---
    // If an external temperature/humidity sensor is available, feeding its readings
    // to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
    // fixed 22C/45%RH as an example.
    sensor.set_compensation(22.0, 45.0).expect("set comp");  // Set compensation, (temp_celsius=22.0, rh_percent=45.0) → ()

    // --- Indoor air quality monitoring loop ---
    // Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
    // AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
    for n in 0..60 {
        let (aqi, tvoc_ppb, eco2_ppm) = sensor.read_air_quality().expect("read air quality");  // Read air quality, () → (u8, f32, f32)
        let label = aqi_label(aqi);
        println!("{}s: AQI={} ({}) TVOC={} ppb eCO2={} ppm", n, aqi, label, tvoc_ppb, eco2_ppm);
        delay.delay_ms(1000);
    }
}
