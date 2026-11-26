//! Complete example for the CCS811 — every API method of the Full driver. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gas::ccs811::SOURCE_DATA_READY;
use periph::chips::gas::Ccs811Full;

esp_app_desc!();

const ADDR: u8 = 0x5A;

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

    // Create CCS811 driver, (i2c, addr=0x5A) → Ccs811Full
    // Stores the bus only; no bus traffic yet.
    let mut chip = Ccs811Full::new(i2c, ADDR);
    // Check HW_ID, start application, mode 1, (delay) → Result<()>
    // HW_ID 0x81, APP_START if the chip is in boot mode, one sample per second.
    chip.init(&mut delay).expect("init");

    // Hardware ID and version, () → Result<(u8 hw_id, u8 hw_version)>
    // HW_ID is 0x81, HW_VERSION 0x1X.
    println!("{:?}", chip.hardware_version().expect("hw"));
    // Firmware versions, () → Result<(Ccs811Version boot, Ccs811Version app)>
    // Bootloader and application firmware, each major.minor.trivial.
    println!("{:?}", chip.firmware_version().expect("fw"));
    // STATUS register, () → Result<u8>
    // Bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR.
    println!("status 0x{:02X}", chip.status().expect("status"));
    // ERROR_ID register, () → Result<u8>
    // Zero unless STATUS.ERROR is set.
    println!("error 0x{:02X}", chip.error().expect("error"));

    // Next sample, (delay) → Result<Ccs811Measurement {eco2_ppm ppm, tvoc_ppb ppb}>
    // Waits for DATA_READY, then reads eCO2 and TVOC.
    println!("{:?}", chip.read(&mut delay).expect("read"));
    // eCO2 only, (delay) → Result<u16 ppm>
    // Equivalent CO2 estimated from the VOC level, 400-8192 ppm.
    println!("{} ppm", chip.read_eco2(&mut delay).expect("eco2"));
    // TVOC only, (delay) → Result<u16 ppb>
    // Total volatile organic compounds, 0-1187 ppb.
    println!("{} ppb", chip.read_tvoc(&mut delay).expect("tvoc"));
    // Raw sensor data, () → Result<(u8 current_ua µA, f32 voltage_v V)>
    // Sense current and the voltage across the sensor.
    println!("{:?}", chip.read_raw().expect("raw"));
    // New sample waiting, () → Result<bool>
    // False right after a read, because reading the result clears DATA_READY.
    println!("ready {}", chip.data_ready().expect("ready"));

    // Compensation data, (humidity_rh %, temperature_c °C) → Result<()>
    // Real ambient values improve accuracy; the chip assumes 50 %RH and 25 °C otherwise.
    chip.set_environment(45.0, 22.5).expect("environment");
    // NTC circuit voltages, () → Result<(u16 v_ref_mv mV, u16 v_ntc_mv mV)>
    // Only meaningful with a reference resistor and NTC wired to the AUX pin.
    println!("{:?}", chip.read_ntc().expect("ntc"));
    // NTC resistance, (r_ref_ohm Ω) → Result<f32 Ω>
    // V_NTC × R_REF / V_REF; InvalidArgument without an NTC circuit (V_REF = 0).
    println!("{:?}", chip.ntc_resistance(100000.0));

    // Threshold boundaries, (low_medium_ppm, medium_high_ppm, hysteresis_ppm) → Result<()>
    // eCO2 zones used by the threshold interrupt.
    chip.set_thresholds(1000, 2000, 50).expect("thresholds");
    // Algorithm baseline, () → Result<u16>
    // Save it to non-volatile storage about once a day.
    let baseline = chip.get_baseline().expect("baseline");
    println!("baseline 0x{:04X}", baseline);
    // Restore baseline, (baseline) → Result<()>
    // Restore a saved value only after about 20 min of warm-up.
    chip.set_baseline(baseline).expect("baseline");

    // nINT on new data, (source) → Result<()>
    // Sets INT_DATARDY and keeps the drive mode.
    chip.enable_interrupt(SOURCE_DATA_READY).expect("enable");
    sleep_ms!(1200);
    // STATUS without releasing nINT, () → Result<u8>
    // DATA_READY shows whether a sample is pending; wire nINT to an ISR to react to it.
    println!("poll 0x{:02X}", chip.poll_interrupt().expect("poll"));
    // nINT off, (source) → Result<()>
    // Clears INT_DATARDY.
    chip.disable_interrupt(SOURCE_DATA_READY).expect("disable");

    // Drive mode, (mode 0-4) → Result<()>
    // Mode 4: raw data every 250 ms, no eCO2/TVOC updates.
    chip.set_mode(4).expect("mode");
    sleep_ms!(500);
    // Raw sensor data, () → Result<(u8 current_ua µA, f32 voltage_v V)>
    // RAW_DATA keeps updating in mode 4.
    println!("{:?}", chip.read_raw().expect("raw"));
    // Drive mode, (mode 0-4) → Result<()>
    // Idle before switching to another mode.
    chip.set_mode(0).expect("mode");
    // Drive mode, (mode 0-4) → Result<()>
    // Back to one sample per second.
    chip.set_mode(1).expect("mode");

    // Software reset, (delay) → Result<()>
    // SW_RESET into boot mode, then APP_START and mode 1 again.
    chip.soft_reset(&mut delay).expect("reset");

    loop {
        sleep_ms!(1000);
    }
}
