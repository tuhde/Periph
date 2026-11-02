#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::adc_dac::{Hy3131Full, Hy3131Minimal};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2.reborrow(), Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3.reborrow())
        .with_miso(peripherals.GPIO4.reborrow())
        .with_sck(peripherals.GPIO5.reborrow());
    let cs = Output::new(peripherals.GPIO6.reborrow(), esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut chip = Hy3131Full::new(device, Delay::new(), 1.2).expect("init HY3131 Full");
    let raw = chip.read_raw().expect("read raw");
    if (-8_388_608..=8_388_607).contains(&raw) { println!("PASS read_raw_in_range"); passed += 1; }
    else                                        { println!("FAIL read_raw_in_range"); failed += 1; }
    let v = chip.read_voltage().expect("read voltage");
    if v.is_finite() { println!("PASS read_voltage_finite"); passed += 1; }
    else             { println!("FAIL read_voltage_finite"); failed += 1; }

    chip.configure_ad1(4, 2, 0, 0, 0, 0).expect("configure_ad1");
    if chip.read_raw().is_ok() { println!("PASS read_raw_after_configure"); passed += 1; }
    else                       { println!("FAIL read_raw_after_configure"); failed += 1; }

    chip.enable_ad2(5, 1, 0, false, false, 0, false).expect("ad2");
    let ad2 = chip.read_ad2_raw().expect("ad2 raw");
    if (-262_144..=262_143).contains(&ad2) { println!("PASS read_ad2_raw_in_range"); passed += 1; }
    else                                   { println!("FAIL read_ad2_raw_in_range"); failed += 1; }
    chip.enable_peak_hold(0).expect("pkh");
    let (lo, hi) = chip.read_peak().expect("peak");
    if lo <= hi { println!("PASS read_peak_ordered"); passed += 1; }
    else        { println!("FAIL read_peak_ordered"); failed += 1; }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    let delay = Delay::new();
    loop { delay.delay_millis(1000); }
}
