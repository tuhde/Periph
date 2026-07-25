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

    let (major, minor, release) = sensor.get_firmware_version().expect("get fw");  // Get firmware version, () → (u8, u8, u8)
                                                                                     // switches to IDLE, issues GET_APPVER, returns to STANDARD
    println!("Firmware: {}.{}.{}", major, minor, release);

    sensor.set_compensation(25.0, 50.0).expect("set comp");  // Set compensation, (temp_celsius, rh_percent) → ()
                                                              // improves accuracy with external T/RH readings

    sensor.configure_interrupt(true, false, false, true, false).expect("config int");  // Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → ()
                                                                                       // sets INTn pin behavior for new data notification

    println!("Waiting for warm-up...");
    loop {                                                        // Wait for valid data, () → blocks until warm
        let status = sensor.wait_for_new_data(2000).unwrap();
        if (status >> 2) & 0x03 == 0 { break; }
    }

    let tvoc = sensor.read_tvoc().expect("read tvoc");       // Read TVOC, () → f32 ppb
    let eco2 = sensor.read_eco2().expect("read eco2");       // Read eCO2, () → f32 ppm
    let aqi = sensor.read_aqi().expect("read aqi");          // Read AQI, () → u8 1–5
    let ethanol = sensor.read_ethanol().expect("read ethanol");  // Read ethanol, () → f32 ppb
                                                              // alias of DATA_TVOC at 0x22
    let r1 = sensor.read_raw_resistance(1).expect("read r1");  // Read raw resistance, (sensor=1 or 4) → f32 Ohms
    let r4 = sensor.read_raw_resistance(4).expect("read r4");  // Read raw resistance, (sensor=1 or 4) → f32 Ohms
    let (temp_actual, rh_actual) = sensor.read_compensation_actuals().expect("read actuals");  // Read compensation actuals, () → (f32, f32)
                                                              // returns T/RH values used by sensor

    println!("TVOC={} ppb, eCO2={} ppm, AQI={}", tvoc, eco2, aqi);
    println!("Ethanol={} ppb, R1={} Ohm, R4={} Ohm", ethanol, r1, r4);
    println!("Actual T={:.1} C, RH={:.1} %", temp_actual, rh_actual);

    sensor.sleep().expect("sleep");                          // Enter deep sleep, () → ()
                                                              // reduces current to ~10 uA
    delay.delay_ms(1000);
    sensor.wake().expect("wake");                            // Wake and resume sensing, () → ()
                                                              // transitions IDLE then STANDARD
}
