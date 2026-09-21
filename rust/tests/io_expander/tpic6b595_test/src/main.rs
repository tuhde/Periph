use linux_embedded_hal::{CdevPin, SpidevBus};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use periph::chips::io_expander::{Tpic6b595Minimal, Tpic6b595Full};
use embedded_hal::digital::{OutputPin, StatefulOutputPin};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

macro_rules! check_eq {
    ($a:expr, $b:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $a == $b {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {} (got {:?}, expected {:?})", $label, $a, $b);
            $failed += 1;
        }
    };
}

fn main() {
    let spi_bus: u8 = std::env::var("SIPO_SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SIPO_SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let rck_line: u32 = std::env::var("SIPO_RCK").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let srclr_line: u32 = std::env::var("SIPO_SRCLR").ok().and_then(|v| v.parse().ok()).unwrap_or(6);
    let g_line: u32 = std::env::var("SIPO_G").ok().and_then(|v| v.parse().ok()).unwrap_or(13);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let open_spi = || -> SpidevBus {
        let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spidev");
        spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build())
            .expect("configure spidev");
        SpidevBus(spi)
    };

    // --- Tpic6b595Minimal (single device) ---
    let rck1 = CdevPin::open("/dev/gpiochip0", rck_line, false).expect("open rck line (minimal)");

    let chip1 = Tpic6b595Minimal::new(open_spi(), rck1, None, None, 1).expect("init TPIC6B595 minimal");

    check_eq!(chip1.shadow_byte(0), 0x00, "init_shadow_0", passed, failed);

    chip1.fill(true).expect("fill true");
    check_eq!(chip1.shadow_byte(0), 0xFF, "fill_true_shadow", passed, failed);
    chip1.fill(false).expect("fill false");
    check_eq!(chip1.shadow_byte(0), 0x00, "fill_false_shadow", passed, failed);
    chip1.off().expect("off");
    check_eq!(chip1.shadow_byte(0), 0x00, "off_shadow", passed, failed);

    chip1.write_port(0, 0xA5).expect("write_port");
    check_eq!(chip1.shadow_byte(0), 0xA5, "write_port_0xa5_shadow", passed, failed);

    let mut p0 = chip1.pin(0);
    p0.set_high().expect("set_high");
    check_eq!(chip1.shadow_byte(0) & 0x01, 1u8, "pin_on_shadow_bit", passed, failed);
    p0.set_low().expect("set_low");
    check_eq!(chip1.shadow_byte(0) & 0x01, 0u8, "pin_off_shadow_bit", passed, failed);

    let set_high_after_low = p0.is_set_high().expect("is_set_high");
    check_eq!(set_high_after_low, false, "is_set_high_after_low", passed, failed);
    let set_low_after_low = p0.is_set_low().expect("is_set_low");
    check_eq!(set_low_after_low, true, "is_set_low_after_low", passed, failed);

    chip1.write_port(0, 0x00).expect("write_port reset");

    // --- Tpic6b595Full (two cascaded devices) ---
    let rck2   = CdevPin::open("/dev/gpiochip0", rck_line,   false).expect("open rck line (full)");
    let srclr2 = CdevPin::open("/dev/gpiochip0", srclr_line, true ).expect("open srclr line (full)");
    let g2     = CdevPin::open("/dev/gpiochip0", g_line,     false).expect("open g line (full)");

    let mut chip2 = Tpic6b595Full::new(open_spi(), rck2, Some(srclr2), Some(g2), 2).expect("init TPIC6B595 full");

    check_eq!(chip2.inner.shadow_byte(0), 0x00, "cascaded_init_shadow_0", passed, failed);
    check_eq!(chip2.inner.shadow_byte(1), 0x00, "cascaded_init_shadow_1", passed, failed);

    chip2.write_port(0, 0x01).expect("write_port 0");
    chip2.write_port(1, 0x80).expect("write_port 1");
    check_eq!(chip2.inner.shadow_byte(0), 0x01, "cascaded_write_port_0", passed, failed);
    check_eq!(chip2.inner.shadow_byte(1), 0x80, "cascaded_write_port_1", passed, failed);

    chip2.clear().expect("clear");
    check_true!(true, "clear_accepted", passed, failed);
    chip2.set_output_enable(false).expect("set_output_enable false");
    check_true!(true, "set_output_enable_false_accepted", passed, failed);
    chip2.set_output_enable(true).expect("set_output_enable true");
    check_true!(true, "set_output_enable_true_accepted", passed, failed);

    chip2.write_all(&[0xA5, 0x5A]).expect("write_all");
    check_eq!(chip2.inner.shadow_byte(0), 0xA5, "write_all_shadow_0", passed, failed);
    check_eq!(chip2.inner.shadow_byte(1), 0x5A, "write_all_shadow_1", passed, failed);

    chip2.write_all(&[0xFF]).expect("write_all pad");
    check_eq!(chip2.inner.shadow_byte(0), 0xFF, "write_all_pad_shadow_0", passed, failed);
    check_eq!(chip2.inner.shadow_byte(1), 0x00, "write_all_pad_shadow_1", passed, failed);

    chip2.write_all(&[0x12, 0x34, 0x56]).expect("write_all truncate");
    check_eq!(chip2.inner.shadow_byte(0), 0x12, "write_all_truncate_shadow_0", passed, failed);
    check_eq!(chip2.inner.shadow_byte(1), 0x34, "write_all_truncate_shadow_1", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
