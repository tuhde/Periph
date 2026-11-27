//! Complete example for the Si1145 — every API method of the Full driver. (ESP32-S3: SDA GPIO1, SCL GPIO2).
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
    // Fixed address 0x60 (older silicon: 0x5A); stores the bus only, no bus traffic yet.
    let mut sensor = Si1145Full::new(bus, addr);
    // Check PART_ID, HW_KEY, UV + ALS + PS1, (delay) → Result<()>
    // Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.
    sensor.init(&mut delay).expect("init");

    // Identity registers, () → Result<(part_id, rev_id, seq_id)>
    // PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
    println!("{:?}", sensor.read_id().expect("id"));
    // RESPONSE decoded, () → Result<Response {counter, error}>
    // 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).
    println!("{:?}", sensor.read_response().expect("response"));

    // Forced PS + ALS, (delay) → Result<Reading {proximity, uv_index, visible, infrared}>
    // PSALS_FORCE, waits for ALS_INT and PS1_INT; raw counts and the UV index (AUX_DATA / 100).
    println!("{:?}", sensor.read(&mut delay).expect("read"));
    // Forced PS, (delay, channel) → Result<u16>
    // PS1 only; PS2/PS3 exist on the Si1146/47.
    println!("proximity {}", sensor.read_proximity(&mut delay, 1).expect("proximity"));
    // Forced ALS, (delay) → Result<f32>
    // UV index with the default UV coefficients 7B 6B 01 00.
    println!("UV {:.2}", sensor.read_uv_index(&mut delay).expect("uv"));
    // Forced ALS, (delay) → Result<u16>
    // Raw visible ambient light.
    println!("visible {}", sensor.read_visible(&mut delay).expect("visible"));
    // Forced ALS, (delay) → Result<u16>
    // Raw infrared ambient light.
    println!("infrared {}", sensor.read_infrared(&mut delay).expect("infrared"));
    // Data registers, () → Result<Raw {visible, infrared, ps1, ps2, ps3, aux}>
    // No new measurement; the latest values of 0x22–0x2D.
    println!("{:?}", sensor.read_raw().expect("raw"));
    // AUX temperature, (delay) → Result<u16>
    // Relative die temperature; CHLIST is switched to EN_AUX and restored.
    println!("temperature raw {}", sensor.read_temperature_raw(&mut delay).expect("temperature"));
    // AUX VDD, (delay) → Result<u16>
    // Supply voltage on the auxiliary channel.
    println!("VDD raw {}", sensor.read_vdd_raw(&mut delay).expect("vdd"));

    // CHLIST, (delay, uv, visible, infrared, ps1, ps2, ps3, aux) → Result<()>
    // UV and AUX share AUX_DATA, so only one of them can be enabled.
    sensor.set_channels(&mut delay, true, true, true, true, false, false, false).expect("channels");
    // LED drive, (led, level) → Result<()>
    // Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
    sensor.set_led_current(1, 8).expect("led");
    // PS LED select, (delay, channel, leds) → Result<()>
    // PS1 drives LED1; the Si1145 has no LED2/LED3.
    sensor.set_ps_led(&mut delay, 1, 0x01).expect("ps led");
    // PS photodiode, (delay, channel, large) → Result<()>
    // Large IR photodiode (0x03); false selects the small one.
    sensor.set_ps_photodiode(&mut delay, 1, true).expect("photodiode");
    // ADC gains, (delay, visible, infrared, proximity) → Result<()>
    // Integration time × 2^n, recovery counts at the one's complement of the gain.
    sensor.set_gain(&mut delay, 0, 0, 0).expect("gain");
    // Signal range, (delay, visible, infrared, proximity) → Result<()>
    // true divides the sensitivity by 14.5 (direct sunlight).
    sensor.set_high_range(&mut delay, false, false, false).expect("range");
    // Result alignment, (delay, visible, infrared, ps1, ps2, ps3) → Result<()>
    // true reports the 16 LSBs instead of the 16 MSBs of the ADC result.
    sensor.set_alignment(&mut delay, false, false, false, false, false).expect("alignment");
    // UCOEF0..3, (ucoef [u8; 4]) → Result<()>
    // 7B 6B 01 00 make AUX_DATA the UV index × 100.
    sensor.set_uv_coefficients(si1145::UCOEF_DEFAULT).expect("ucoef");
    // GET_CAL, (delay) → Result<[u8; 12]>
    // Factory calibration bytes; overwrites the data registers.
    println!("{:?}", sensor.read_calibration(&mut delay).expect("calibration"));

    // IRQ_STATUS, () → Result<u8 sources>
    // Reads and clears pending flags.
    sensor.poll_interrupt().expect("poll");
    // Autonomous mode, (delay, interval_s, proximity, als) → Result<()>
    // MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
    sensor.start_autonomous(&mut delay, 0.1, true, true).expect("autonomous");
    sleep_ms!(500);
    // IRQ_STATUS, () → Result<u8 sources>
    // SOURCE_ALS | SOURCE_PS1 when both finished at least once.
    println!("sources 0x{:02X}", sensor.poll_interrupt().expect("poll"));
    // PSALS_PAUSE, (delay) → Result<()>
    // Stops the autonomous loop.
    sensor.pause(&mut delay).expect("pause");
    // Forced measurement without waiting, (delay, proximity, als) → Result<()>
    // PS_FORCE; the result arrives when IRQ_STATUS flags PS1.
    sensor.force(&mut delay, true, false).expect("force");
    sleep_ms!(50);
    println!("ps1 {}", sensor.read_raw().expect("raw").ps1);

    // INT_CFG + IRQ_ENABLE, (source) → Result<()>
    // INT pin (active low) driven for ALS and PS1 completions; wire it to an ISR and call poll_interrupt.
    sensor.enable_interrupt(si1145::SOURCE_ALS | si1145::SOURCE_PS1).expect("enable");
    sensor.force(&mut delay, true, true).expect("force");
    sleep_ms!(200);
    println!("sources 0x{:02X}", sensor.poll_interrupt().expect("poll"));
    // IRQ_ENABLE clear, (source) → Result<()>
    // CMD_INT is not used by default.
    sensor.disable_interrupt(si1145::SOURCE_CMD).expect("disable");

    // I2C_ADDR + BUSADDR, (delay, address) → Result<()>
    // Volatile; the driver follows the new address. Here it stays 0x60.
    sensor.set_i2c_address(&mut delay, 0x60).expect("address");
    // RESET + HW_KEY + defaults, (delay) → Result<()>
    // Software reset, 10 ms wait, then the Minimal configuration again.
    sensor.reset(&mut delay).expect("reset");

    loop {
        sleep_ms!(1000);
    }
}
