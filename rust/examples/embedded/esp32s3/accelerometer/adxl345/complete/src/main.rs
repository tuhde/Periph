#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::accelerometer::{Adxl345Full, FIFO_STREAM, INT_WATERMARK};

esp_app_desc!();

const ADDR: u8 = 0x53;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut accel = Adxl345Full::new(i2c, ADDR, false).expect("init ADXL345"); // Create ADXL345 Full driver, (i2c, addr=0x53, spi=false)

    accel.set_range(4).expect("set_range");                            // Set measurement range, (range_g) → () g
                                                                            // selects ±4 g; FULL_RES preserved so scale stays 3.9 mg/LSB
    accel.set_data_rate(200.0).expect("set_data_rate");                // Set output data rate, (rate_hz) → () Hz
                                                                            // picks the nearest supported value (200 Hz)
    accel.set_low_power(false).expect("set_low_power");                // Set low-power mode, (enabled) → ()
                                                                            // normal-power mode; LOW_POWER bit in BW_RATE cleared
    accel.calibrate_offset(0.0, 0.0, 1.0, 64).expect("calibrate");   // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → () g, g, g
                                                                            // averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
    accel.set_tap_detection(0.5, 10.0, 0x07, false).expect("tap");    // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → () g, ms
                                                                            // 0.5 g threshold, 10 ms duration, all axes, no suppress
    accel.set_double_tap(50.0, 200.0).expect("dtap");                  // Configure double-tap, (latency_ms, window_ms) → () ms, ms
                                                                            // 50 ms latency, 200 ms window between taps
    accel.set_fifo_mode(FIFO_STREAM, 16).expect("fifo");               // Configure FIFO, (mode, samples=16) → ()
                                                                            // stream mode, watermark 16 entries
    accel.set_interrupt(INT_WATERMARK, true, 1).expect("irq");        // Configure interrupt, (source, enabled, pin=1) → ()
                                                                            // enable watermark interrupt on INT1

    let (x, y, z) = accel.read().expect("read");                       // Read 3-axis acceleration, () → (f32, f32, f32) g
                                                                            // single-shot burst read of all 6 data bytes
    let mut fifo_buf = [(0.0f32, 0.0f32, 0.0f32); 32];
    let n_samples = accel.read_fifo(&mut fifo_buf).expect("read_fifo"); // Drain the FIFO, (out) → usize g
                                                                            // fills up to 32 (x, y, z) samples in *g*
    let count = accel.fifo_count().expect("fifo_count");               // FIFO entries available, () → u8
                                                                            // from FIFO_STATUS register
    let src = accel.read_interrupt_source().expect("read_irq_src");    // Read interrupt source, () → u8
                                                                            // bitmask of active INT_* sources; clears latches

    accel.self_test(false).expect("self_test");                        // Toggle self-test, (enabled) → ()
                                                                            // SELF_TEST bit in DATA_FORMAT cleared
    accel.set_sleep(false, 8).expect("set_sleep");                     // Set sleep mode, (enabled, wakeup_hz=8) → () Hz
                                                                            // wake up; no further state changes
    accel.set_link_mode(false).expect("set_link_mode");                // Set activity/inactivity link, (enabled) → ()
                                                                            // Link bit in POWER_CTL cleared
    accel.set_auto_sleep(false).expect("set_auto_sleep");              // Set auto-sleep, (enabled) → ()
                                                                            // AUTO_SLEEP bit cleared

    println!("x={:.3} y={:.3} z={:.3} g", x, y, z);
    println!("fifo_count={} interrupts=0x{:02X}", count, src);
    println!("samples={}", n_samples);

    loop {
        delay.delay_ms(1000);
    }
}
