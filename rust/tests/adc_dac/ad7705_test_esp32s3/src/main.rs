#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::adc_dac::{AD7705Full, AD7705Minimal, GAIN_128, GAIN_2, GAIN_4, MCLK_2_4576MHZ};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High);
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut chip_min = AD7705Minimal::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7705 Minimal");
    let raw = chip_min.read_raw().expect("read raw");
    if raw <= 65535 { println!("PASS read_raw_in_range"); passed += 1; }
    else            { println!("FAIL read_raw_in_range"); failed += 1; }

    let v = chip_min.read_voltage().expect("read voltage");
    if v >= -2.5 && v <= 2.5 { println!("PASS read_voltage_in_range"); passed += 1; }
    else                     { println!("FAIL read_voltage_in_range"); failed += 1; }

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High);
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();
    let mut chip = AD7705Full::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7705 Full");

    let raw1 = chip.read_raw_channel(1).expect("read raw ch1");
    if raw1 <= 65535 { println!("PASS read_raw_channel1_in_range"); passed += 1; }
    else             { println!("FAIL read_raw_channel1_in_range"); failed += 1; }

    let v1 = chip.read_voltage_channel(1).expect("read voltage ch1");
    if v1 >= -2.5 && v1 <= 2.5 { println!("PASS read_voltage_channel1_in_range"); passed += 1; }
    else                       { println!("FAIL read_voltage_channel1_in_range"); failed += 1; }

    let raw2 = chip.read_raw_channel(2).expect("read raw ch2");
    if raw2 <= 65535 { println!("PASS read_raw_channel2_in_range"); passed += 1; }
    else             { println!("FAIL read_raw_channel2_in_range"); failed += 1; }

    let v2 = chip.read_voltage_channel(2).expect("read voltage ch2");
    if v2 >= -2.5 && v2 <= 2.5 { println!("PASS read_voltage_channel2_in_range"); passed += 1; }
    else                       { println!("FAIL read_voltage_channel2_in_range"); failed += 1; }

    chip.configure(1, GAIN_2, true, false, 60).expect("cfg1");
    chip.configure(2, GAIN_4, false, true, 60).expect("cfg2");
    chip.configure(1, GAIN_128, true, true, 50).expect("cfg1_128");
    println!("PASS configure_accepted"); passed += 1;

    chip.self_calibrate(1).expect("cal1");
    chip.self_calibrate(2).expect("cal2");
    println!("PASS self_calibrate_accepted"); passed += 1;

    chip.system_calibrate_zero(1).expect("syscal0");
    chip.system_calibrate_full(1).expect("syscal_full");
    println!("PASS system_calibrate_accepted"); passed += 1;

    let off1 = chip.get_offset_calibration(1).expect("off1");
    if off1 <= 0xFFFFFF { println!("PASS get_offset_calibration_in_range"); passed += 1; }
    else                { println!("FAIL get_offset_calibration_in_range"); failed += 1; }
    chip.set_offset_calibration(off1, 1).expect("set off1");
    println!("PASS set_offset_calibration_accepted"); passed += 1;

    let gain1 = chip.get_gain_calibration(1).expect("gain1");
    if gain1 <= 0xFFFFFF { println!("PASS get_gain_calibration_in_range"); passed += 1; }
    else                 { println!("FAIL get_gain_calibration_in_range"); failed += 1; }
    chip.set_gain_calibration(gain1, 1).expect("set gain1");
    println!("PASS set_gain_calibration_accepted"); passed += 1;

    let off2 = chip.get_offset_calibration(2).expect("off2");
    if off2 <= 0xFFFFFF { println!("PASS get_offset_calibration_ch2_in_range"); passed += 1; }
    else                { println!("FAIL get_offset_calibration_ch2_in_range"); failed += 1; }
    let gain2 = chip.get_gain_calibration(2).expect("gain2");
    if gain2 <= 0xFFFFFF { println!("PASS get_gain_calibration_ch2_in_range"); passed += 1; }
    else                 { println!("FAIL get_gain_calibration_ch2_in_range"); failed += 1; }

    chip.standby().expect("standby");
    chip.wakeup().expect("wakeup");
    println!("PASS standby_wakeup_accepted"); passed += 1;

    let delay = Delay::new();
    delay.delay_millis(1000);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop { delay.delay_millis(1000); }
}
