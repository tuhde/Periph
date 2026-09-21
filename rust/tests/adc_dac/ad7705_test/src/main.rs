use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::adc_dac::{AD7705Full, AD7705Minimal, GAIN_128, GAIN_2, GAIN_4, MCLK_2_4576MHZ};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip_min = AD7705Minimal::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7705 Minimal");
    let raw = chip_min.read_raw().expect("read raw");
    check_true!(raw <= 65535, "read_raw_in_range", passed, failed);

    let v = chip_min.read_voltage().expect("read voltage");
    check_true!(v >= -2.5 && v <= 2.5, "read_voltage_in_range", passed, failed);

    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spi 2");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi 2");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device 2");
    let mut chip = AD7705Full::new(device, 2.5, MCLK_2_4576MHZ).expect("init AD7705 Full");

    let raw1 = chip.read_raw_channel(1).expect("read raw ch1");
    check_true!(raw1 <= 65535, "read_raw_channel1_in_range", passed, failed);
    let v1 = chip.read_voltage_channel(1).expect("read voltage ch1");
    check_true!(v1 >= -2.5 && v1 <= 2.5, "read_voltage_channel1_in_range", passed, failed);

    let raw2 = chip.read_raw_channel(2).expect("read raw ch2");
    check_true!(raw2 <= 65535, "read_raw_channel2_in_range", passed, failed);
    let v2 = chip.read_voltage_channel(2).expect("read voltage ch2");
    check_true!(v2 >= -2.5 && v2 <= 2.5, "read_voltage_channel2_in_range", passed, failed);

    chip.configure(1, GAIN_2, true, false, 60).expect("cfg1");
    chip.configure(2, GAIN_4, false, true, 60).expect("cfg2");
    chip.configure(1, GAIN_128, true, true, 50).expect("cfg1_128");
    check_true!(true, "configure_accepted", passed, failed);

    chip.self_calibrate(1).expect("cal1");
    chip.self_calibrate(2).expect("cal2");
    check_true!(true, "self_calibrate_accepted", passed, failed);

    chip.system_calibrate_zero(1).expect("syscal0");
    chip.system_calibrate_full(1).expect("syscal_full");
    check_true!(true, "system_calibrate_accepted", passed, failed);

    let off1 = chip.get_offset_calibration(1).expect("off1");
    check_true!(off1 <= 0xFFFFFF, "get_offset_calibration_in_range", passed, failed);
    chip.set_offset_calibration(off1, 1).expect("set off1");
    check_true!(true, "set_offset_calibration_accepted", passed, failed);

    let gain1 = chip.get_gain_calibration(1).expect("gain1");
    check_true!(gain1 <= 0xFFFFFF, "get_gain_calibration_in_range", passed, failed);
    chip.set_gain_calibration(gain1, 1).expect("set gain1");
    check_true!(true, "set_gain_calibration_accepted", passed, failed);

    let off2 = chip.get_offset_calibration(2).expect("off2");
    check_true!(off2 <= 0xFFFFFF, "get_offset_calibration_ch2_in_range", passed, failed);
    let gain2 = chip.get_gain_calibration(2).expect("gain2");
    check_true!(gain2 <= 0xFFFFFF, "get_gain_calibration_ch2_in_range", passed, failed);

    chip.standby().expect("standby");
    chip.wakeup().expect("wakeup");
    check_true!(true, "standby_wakeup_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
