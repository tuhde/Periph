//! SPL06 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x77.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::pressure::spl06;
use periph::chips::pressure::{Spl06FifoEntry, Spl06Full, Spl06Kind};

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = Spl06Full::new(i2c, 0x77);

    esp_println::println!("SPL06 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    let c = chip.coefficients();
    check_true!(c.c0 != 0 || c.c1 != 0 || c.c00 != 0 || c.c10 != 0, "coefficients_decoded");
    let m = chip.read(&mut delay).expect("read");
    check_true!(m.pressure_pa > 30000.0 && m.pressure_pa < 110000.0, "read_pressure_range");
    check_true!(m.temperature_c > -40.0 && m.temperature_c < 85.0, "read_temperature_range");
    let p = chip.read_pressure(&mut delay).expect("pressure");
    check_true!(p > 30000.0 && p < 110000.0, "read_pressure_single");
    let t = chip.read_temperature(&mut delay).expect("temperature");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_single");
    let (praw, traw) = chip.read_raw(&mut delay).expect("raw");
    let cm = chip.compensate(praw, traw, 16, 1).expect("compensate");
    check_true!(cm.pressure_pa > 30000.0 && cm.pressure_pa < 110000.0, "compensate_raw");
    let alt = chip.altitude(&mut delay, 101325.0).expect("altitude");
    check_true!(alt > -500.0 && alt < 9000.0, "altitude_range");
    for osr in [1u8, 8, 128] {
        chip.set_pressure_config(osr, 1).expect("pressure config");
        let r = chip.read_pressure(&mut delay).expect("read");
        check_true!(r > 30000.0 && r < 110000.0, "read_at_osr");
    }
    chip.set_pressure_config(16, 8).expect("pressure config");
    chip.set_temperature_config(1, 8).expect("temperature config");
    chip.start_continuous(true, true).expect("start");
    let rc = chip.read_continuous(&mut delay).expect("continuous");
    check_true!(rc.pressure_pa > 30000.0 && rc.pressure_pa < 110000.0, "read_continuous");
    sleep_ms!(300);
    check_true!(chip.data_ready().expect("ready").0, "data_ready");
    chip.stop().expect("stop");
    chip.flush_fifo().expect("flush");
    chip.enable_fifo().expect("fifo on");
    chip.start_continuous(true, true).expect("start");
    sleep_ms!(1000);
    chip.stop().expect("stop");
    check_true!(!chip.fifo_status().expect("status").0, "fifo_not_empty");
    let mut entries = [Spl06FifoEntry::default(); 32];
    let n = chip.read_fifo(&mut entries).expect("fifo");
    check_true!(n >= 4, "fifo_read");
    check_true!(entries[..n].iter().filter(|e| e.kind == Spl06Kind::Pressure)
        .all(|e| e.value > 30000.0 && e.value < 110000.0), "fifo_values");
    check_true!(chip.fifo_status().expect("status").0, "fifo_empty_after_read");
    chip.disable_fifo().expect("fifo off");
    chip.enable_interrupt(spl06::SOURCE_PRESSURE).expect("enable");
    chip.poll_interrupt().expect("poll");
    chip.read(&mut delay).expect("read");
    check_true!(chip.poll_interrupt().expect("poll") & spl06::SOURCE_PRESSURE != 0, "poll_interrupt_pressure");
    chip.disable_interrupt(spl06::SOURCE_PRESSURE).expect("disable");
    chip.reset(&mut delay).expect("reset");
    let r = chip.read_pressure(&mut delay).expect("read");
    check_true!(r > 30000.0 && r < 110000.0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
