//! Complete example for the SPL06-007 — every API method of the Full driver. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::spl06::SOURCE_PRESSURE;
use periph::chips::pressure::{Spl06FifoEntry, Spl06Full};

esp_app_desc!();

const ADDR: u8 = 0x77;

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

    // Create SPL06 driver, (i2c, addr=0x77) → Spl06Full
    // Stores the bus only; no bus traffic yet.
    let mut chip = Spl06Full::new(i2c, ADDR);
    // Check ID, read coefficients, apply defaults, (delay) → Result<()>
    // ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature.
    chip.init(&mut delay).expect("init");

    // Decoded calibration, () → Spl06Coefficients
    // The nine factory coefficients used by every compensation.
    println!("{:?}", chip.coefficients());
    // Temperature and pressure, (delay) → Result<Spl06Measurement>
    // Command mode: one temperature, then one pressure measurement.
    println!("{:?}", chip.read(&mut delay).expect("read"));
    // Pressure, (delay) → Result<f32 Pa>
    // Measures temperature first, because the compensation needs it.
    println!("{:.2} Pa", chip.read_pressure(&mut delay).expect("pressure"));
    // Temperature, (delay) → Result<f32 °C>
    // One temperature measurement only.
    println!("{:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Raw results, (delay) → Result<(i32 praw, i32 traw)>
    // Signed 24-bit values from the result registers.
    let (praw, traw) = chip.read_raw(&mut delay).expect("raw");
    // Compensate raw values, (praw, traw, p_oversampling, t_oversampling) → Result<Spl06Measurement>
    // Pure computation with the stored coefficients; no bus access.
    println!("{:?}", chip.compensate(praw, traw, 16, 1).expect("compensate"));
    // Altitude, (delay, sea_level_pa) → Result<f32 m>
    // International barometric formula against the given sea-level pressure.
    println!("{:.2} m", chip.altitude(&mut delay, 101325.0).expect("altitude"));

    // Pressure oversampling and rate, (oversampling, rate) → Result<()>
    // 64x oversampling, 4 background readings per second; sets P_SHIFT.
    chip.set_pressure_config(64, 4).expect("pressure config");
    // Temperature oversampling and rate, (oversampling, rate) → Result<()>
    // 2x oversampling, 4 per second; always keeps the external sensor.
    chip.set_temperature_config(2, 4).expect("temperature config");
    // Background mode, (pressure, temperature) → Result<()>
    // MEAS_CTRL = 111: the chip measures both on its own.
    chip.start_continuous(true, true).expect("start");
    // Latest background result, (delay) → Result<Spl06Measurement>
    // Waits for both ready flags; does not start a measurement.
    println!("{:?}", chip.read_continuous(&mut delay).expect("continuous"));
    // Ready flags, () → Result<(bool pressure_ready, bool temperature_ready)>
    // MEAS_CFG.PRS_RDY and TMP_RDY.
    println!("{:?}", chip.data_ready().expect("ready"));
    // Standby, () → Result<()>
    // MEAS_CTRL = 000.
    chip.stop().expect("stop");

    // FIFO on, () → Result<()>
    // Background results now queue in the 32-entry FIFO.
    chip.enable_fifo().expect("fifo on");
    // Background mode, (pressure, temperature) → Result<()>
    // Fill the FIFO for a second.
    chip.start_continuous(true, true).expect("start");
    sleep_ms!(1000);
    // Standby, () → Result<()>
    // Stop adding entries.
    chip.stop().expect("stop");
    // FIFO state, () → Result<(bool empty, bool full)>
    // FIFO_STS flags.
    println!("{:?}", chip.fifo_status().expect("fifo status"));
    let mut entries = [Spl06FifoEntry::default(); 32];
    // Drain FIFO, (&mut [Spl06FifoEntry]) → Result<usize>
    // Each entry is Spl06Kind::Pressure (Pa) or Temperature (°C), in measured order.
    let n = chip.read_fifo(&mut entries).expect("fifo");
    println!("{} FIFO entries {:?}", n, &entries[..n]);
    // Flush FIFO, () → Result<()>
    // Discards any remaining entries.
    chip.flush_fifo().expect("flush");
    // FIFO off, () → Result<()>
    // Results go to the result registers again.
    chip.disable_fifo().expect("fifo off");

    // Enable interrupt source, (source) → Result<()>
    // Pressure-ready interrupt on SDO (active low); wire the pin yourself.
    chip.enable_interrupt(SOURCE_PRESSURE).expect("enable");
    // Read and clear INT_STS, () → Result<u8>
    // Bitmask of SOURCE_PRESSURE / SOURCE_TEMPERATURE / SOURCE_FIFO_FULL.
    println!("INT_STS 0x{:02X}", chip.poll_interrupt().expect("poll"));
    // Disable interrupt source, (source) → Result<()>
    // Clears CFG_REG.INT_PRS.
    chip.disable_interrupt(SOURCE_PRESSURE).expect("disable");
    // Soft reset, (delay) → Result<()>
    // RESET = 0x09, re-reads the coefficients and restores the init defaults.
    chip.reset(&mut delay).expect("reset");

    loop {
        sleep_ms!(1000);
    }
}
