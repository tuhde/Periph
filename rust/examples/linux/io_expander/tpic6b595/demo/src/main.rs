// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
//
// Hardware:
//   Two cascaded TPIC6B595s driving 16 LEDs as an automotive-cluster-style
//   indicator bank (DRAIN0..DRAIN7 on each device). Each LED's anode goes to
//   the supply through a series resistor and its cathode to a DRAIN pin;
//   writing HIGH turns the LED on (active-low via the DMOS sink).
//
// The demo walks a single lit LED back and forth across all 16 outputs and,
// every few sweeps, blanks every output for half a second via
// set_output_enable(false) to demonstrate glitch-free global blanking. The
// shadow register is untouched across the blank, so the chase pattern resumes
// exactly where it left off.
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, SpidevBus};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use periph::chips::io_expander::Tpic6b595Full;
use std::thread::sleep;
use std::time::Duration;

const NUM_DEVICES: u8 = 2;
const NUM_OUTPUTS: i32 = (NUM_DEVICES as i32) * 8;

fn main() {
    let spi_bus: u8 = std::env::var("SIPO_SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SIPO_SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let rck_line: u32 = std::env::var("SIPO_RCK").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let srclr_line: u32 = std::env::var("SIPO_SRCLR").ok().and_then(|v| v.parse().ok()).unwrap_or(6);
    let g_line: u32 = std::env::var("SIPO_G").ok().and_then(|v| v.parse().ok()).unwrap_or(13);

    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build())
        .expect("configure spidev");

    let mut gpio_chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let rck = CdevPin::new(
        gpio_chip.get_line(rck_line).expect("get rck line")
            .request(LineRequestFlags::OUTPUT, 0, "tpic6b595_demo").expect("request rck line"),
    ).expect("rck pin");
    let srclr = CdevPin::new(
        gpio_chip.get_line(srclr_line).expect("get srclr line")
            .request(LineRequestFlags::OUTPUT, 1, "tpic6b595_demo").expect("request srclr line"),
    ).expect("srclr pin");
    let g = CdevPin::new(
        gpio_chip.get_line(g_line).expect("get g line")
            .request(LineRequestFlags::OUTPUT, 0, "tpic6b595_demo").expect("request g line"),
    ).expect("g pin");

    let mut chip = Tpic6b595Full::new(SpidevBus(spi), rck, Some(srclr), Some(g), NUM_DEVICES) // Create TPIC6B595 full driver, (spi, rck, srclr, g, num_devices=2) → Result
        .expect("init TPIC6B595 full");
                                                                                    // two cascaded devices — 16 outputs total; outputs start OFF

    let mut position: i32 = 0;
    let mut direction: i32 = 1;
    let mut sweep_count: u32 = 0;
    const BLANK_EVERY: u32 = 3;
    const BLANK_MS: u64   = 500;

    loop {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use write_all() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        let mut bytes = [0u8; NUM_DEVICES as usize];
        let port = (position / 8) as usize;
        let bit  = (position % 8) as u8;
        bytes[port] = 1u8 << bit;
        chip.write_all(&bytes).expect("write_all");                                  // Write all device bytes, (values=[0x01, 0x80]) → Result<(), E>

        println!("position={}  bytes=[0x{:02X}, 0x{:02X}]", position, bytes[0], bytes[1]);

        // --- Periodically blank every output via G, then resume ---
        // set_output_enable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweep_count += 1;
        if sweep_count % BLANK_EVERY == 0 {
            chip.set_output_enable(false).expect("set_output_enable false");         // Force every output off via G, (enabled=false) → Result<(), E>
                                                                                     // the chase pattern's shadow state is preserved
            println!("  blanked via G for {} ms", BLANK_MS);
            sleep(Duration::from_millis(BLANK_MS));
            chip.set_output_enable(true).expect("set_output_enable true");           // Re-enable outputs, (enabled=true) → Result<(), E>
                                                                                     // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if position >= NUM_OUTPUTS - 1 || position <= 0 {
            direction = -direction;
            sleep(Duration::from_millis(100));
        } else {
            sleep(Duration::from_millis(80));
        }
    }
}
