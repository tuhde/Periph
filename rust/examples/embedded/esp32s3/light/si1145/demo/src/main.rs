//! Demo for the Si1145 — proximity switch with UV monitor. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::light::si1145;
use periph::chips::light::Si1145Full;

esp_app_desc!();

const ADDR: u8 = 0x60;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let bus = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let addr = ADDR;

    // Create Si1145 driver, (i2c, addr=0x60) → Si1145Full
    let mut sensor = Si1145Full::new(bus, addr);
    // Check PART_ID, HW_KEY, UV + ALS + PS1, (delay) → Result<()>
    sensor.init(&mut delay).expect("init");

    // --- Identity and calibration ---
    // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the factory
    // calibration bytes.
    let (part, rev, seq) = sensor.read_id().expect("id");               // Identity registers, () → Result<(u8, u8, u8)>
    println!("PART_ID 0x{:02X}  REV_ID 0x{:02X}  SEQ_ID 0x{:02X}", part, rev, seq);
    println!("calibration {:?}", sensor.read_calibration(&mut delay).expect("cal")); // GET_CAL, (delay) → Result<[u8; 12]>
    println!("{:?}", sensor.read_response().expect("response"));         // RESPONSE, () → Result<Response>

    // --- Proximity, UV index and light for 10 s ---
    // Proximity counts rise as a hand approaches the LED/sensor window.
    for i in 0..10 {
        let r = sensor.read(&mut delay).expect("read");                 // Forced PS + ALS, (delay) → Result<Reading>
        println!("{:2} s  prox {:5}  UV {:5.2}  vis {:5}  ir {:5}", i, r.proximity, r.uv_index, r.visible, r.infrared);
        sleep_ms!(1000);
    }

    // --- LED current and photodiode ---
    // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
    // photodiode a fraction of the large one.
    println!("Hold a hand above the sensor");
    sleep_ms!(3000);
    for level in [1u8, 4, 8, 15] {
        sensor.set_led_current(1, level).expect("led");                 // LED drive, (led, level) → Result<()>
        println!("LED level {:2} -> {}", level, sensor.read_proximity(&mut delay, 1).expect("prox")); // Forced PS, (delay, channel) → Result<u16>
    }
    sensor.set_led_current(1, 3).expect("led");
    for large in [false, true] {
        sensor.set_ps_photodiode(&mut delay, 1, large).expect("pd");    // PS photodiode, (delay, channel, large) → Result<()>
        println!("{} photodiode -> {}", if large { "large" } else { "small" }, sensor.read_proximity(&mut delay, 1).expect("prox"));
    }
    sensor.set_ps_led(&mut delay, 1, 0x01).expect("ps led");            // PS LED select, (delay, channel, leds) → Result<()>

    // --- Gain and high range ---
    // Each gain step doubles the integration time; the high range divides the sensitivity by 14.5
    // for direct sunlight.
    for gain in [0u8, 2, 4] {
        sensor.set_gain(&mut delay, gain, gain, 0).expect("gain");      // ADC gains, (delay, visible, infrared, proximity) → Result<()>
        let vis = sensor.read_visible(&mut delay).expect("vis");        // Forced ALS, (delay) → Result<u16>
        let ir = sensor.read_infrared(&mut delay).expect("ir");         // Forced ALS, (delay) → Result<u16>
        println!("gain {}  vis {}  ir {}", gain, vis, ir);
    }
    sensor.set_gain(&mut delay, 0, 0, 0).expect("gain");
    sensor.set_high_range(&mut delay, true, true, false).expect("range"); // Signal range, (delay, visible, infrared, proximity) → Result<()>
    let vis = sensor.read_visible(&mut delay).expect("vis");
    let uv = sensor.read_uv_index(&mut delay).expect("uv");             // Forced ALS, (delay) → Result<f32>
    println!("high range  vis {}  UV {:.2}", vis, uv);
    sensor.set_high_range(&mut delay, false, false, false).expect("range");
    sensor.set_alignment(&mut delay, false, false, false, false, false).expect("align"); // Result alignment, (delay, ...) → Result<()>
    sensor.set_uv_coefficients(si1145::UCOEF_DEFAULT).expect("ucoef");  // UCOEF0..3, (ucoef) → Result<()>

    // --- Autonomous mode with interrupt events and a proximity switch ---
    // PS + ALS every 100 ms; each finished PS1 measurement sets IRQ_STATUS (and INT, when wired).
    const THRESHOLD: u16 = 2000;
    sensor.set_channels(&mut delay, true, true, true, true, false, false, false).expect("channels"); // CHLIST, (delay, ...) → Result<()>
    sensor.enable_interrupt(si1145::SOURCE_PS1).expect("enable");       // INT_CFG + IRQ_ENABLE, (source) → Result<()>
    sensor.start_autonomous(&mut delay, 0.1, true, true).expect("auto"); // Autonomous mode, (delay, interval_s, proximity, als) → Result<()>
    let mut near = false;
    let mut events = 0u32;
    for _ in 0..50 {
        if sensor.poll_interrupt().expect("poll") != 0 {                // IRQ_STATUS, () → Result<u8>
            events += 1;
        }
        let ps1 = sensor.read_raw().expect("raw").ps1;                  // Data registers, () → Result<Raw>
        if (ps1 > THRESHOLD) != near {
            near = ps1 > THRESHOLD;
            println!("output {} (prox {})", if near { "ON" } else { "OFF" }, ps1);
        }
        sleep_ms!(100);
    }
    sensor.pause(&mut delay).expect("pause");                           // PSALS_PAUSE, (delay) → Result<()>
    println!("{} interrupt events", events);
    sensor.disable_interrupt(si1145::SOURCE_PS1).expect("disable");     // IRQ_ENABLE clear, (source) → Result<()>

    // --- Auxiliary channels ---
    let t = sensor.read_temperature_raw(&mut delay).expect("temp");     // AUX temperature, (delay) → Result<u16>
    let v = sensor.read_vdd_raw(&mut delay).expect("vdd");              // AUX VDD, (delay) → Result<u16>
    println!("temperature raw {}  VDD raw {}", t, v);
    sensor.force(&mut delay, false, true).expect("force");              // ALS_FORCE without waiting, (delay, proximity, als) → Result<()>
    sleep_ms!(50);
    println!("{:?}", sensor.read_raw().expect("raw"));

    // --- Address and reset ---
    sensor.set_i2c_address(&mut delay, 0x60).expect("address");         // I2C_ADDR + BUSADDR, (delay, address) → Result<()>
    sensor.reset(&mut delay).expect("reset");                           // RESET + HW_KEY + defaults, (delay) → Result<()>
    println!("after reset {:?}", sensor.read(&mut delay).expect("read"));

    loop {
        sleep_ms!(1000);
    }
}
