use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, SpidevBus};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use periph::chips::io_expander::{Tpic6b595Minimal, Tpic6b595Full};
use embedded_hal::digital::{OutputPin, StatefulOutputPin};

fn open_spi() -> SpidevBus {
    let spi_bus: u8 = std::env::var("SIPO_SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SIPO_SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build())
        .expect("configure spidev");
    SpidevBus(spi)
}

fn main() {
    let rck_line: u32 = std::env::var("SIPO_RCK").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let srclr_line: Option<u32> = std::env::var("SIPO_SRCLR").ok().and_then(|v| v.parse().ok());
    let g_line: Option<u32> = std::env::var("SIPO_G").ok().and_then(|v| v.parse().ok());

    let mut gpio_chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");

    // --- Tpic6b595Minimal ---
    // Scoped so the RCK line is released again before Tpic6b595Full below
    // requests it (only one chip is ever wired up at a time on the demo rig).
    {
        let rck_min = CdevPin::new(
            gpio_chip.get_line(rck_line).expect("get rck line")
                .request(LineRequestFlags::OUTPUT, 0, "tpic6b595_complete").expect("request rck line (minimal)"),
        ).expect("rck pin (minimal)");

        let chip1 = Tpic6b595Minimal::new(open_spi(), rck_min, None::<CdevPin>, None::<CdevPin>, 1)           // Create TPIC6B595 minimal driver, (spi, rck, srclr=None, g=None, num_devices=1) → Result
            .expect("init TPIC6B595 minimal");
                                                                                        // initialises every output to OFF (shadow zeroed, latched once)

        let mut p0 = chip1.pin(0);                                                      // Get pin proxy, (n=0) → ExPin
        p0.set_high().expect("set_high");                                               // Set DMOS output ON, () → Result<(), E>
                                                                                        // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
        p0.set_low().expect("set_low");                                                 // Set DMOS output OFF, () → Result<(), E>
                                                                                        // clears shadow[0] bit 0, retransmits and latches

        let set_high = p0.is_set_high().expect("is_set_high");                          // Read shadow bit, () → Result<bool, E>
                                                                                        // returns the shadow bit (no bus read — SiPo is write-only)
        println!("pin0 is_set_high={}", set_high);

        chip1.fill(true).expect("fill true");                                           // Set every output ON, (value=true) → Result<(), E>
                                                                                        // fills every shadow byte with 0xFF and retransmits — fast "all on" path
        chip1.fill(false).expect("fill false");                                         // Set every output OFF, (value=false) → Result<(), E>
                                                                                        // fills every shadow byte with 0x00 and retransmits — fast "all off" path
        chip1.off().expect("off");                                                      // Turn every output off, () → Result<(), E>
                                                                                        // shorthand for fill(false); the safe initial state

        chip1.write_port(0, 0xA5).expect("write_port");                                 // Write port 0, (port=0, mask=0xA5) → Result<(), E>
                                                                                        // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF
    }

    // --- Tpic6b595Full ---
    let rck_full = CdevPin::new(
        gpio_chip.get_line(rck_line).expect("get rck line")
            .request(LineRequestFlags::OUTPUT, 0, "tpic6b595_complete").expect("request rck line (full)"),
    ).expect("rck pin (full)");
    let srclr_full = srclr_line.map(|o| CdevPin::new(
        gpio_chip.get_line(o).expect("get srclr line")
            .request(LineRequestFlags::OUTPUT, 1, "tpic6b595_complete").expect("request srclr line"),
    ).expect("srclr pin"));
    let g_full = g_line.map(|o| CdevPin::new(
        gpio_chip.get_line(o).expect("get g line")
            .request(LineRequestFlags::OUTPUT, 0, "tpic6b595_complete").expect("request g line"),
    ).expect("g pin"));
    let has_srclr = srclr_full.is_some();
    let has_g = g_full.is_some();

    let mut chip2 = Tpic6b595Full::new(open_spi(), rck_full, srclr_full, g_full, 2)  // Create TPIC6B595 full driver, (spi, rck, srclr, g, num_devices=2) → Result
        .expect("init TPIC6B595 full");
                                                                                    // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    chip2.write_all(&[0x01, 0x80]).expect("write_all");                             // Write all device bytes, (values=[0x01, 0x80]) → Result<(), E>
                                                                                    // updates both shadow bytes and performs one transmit + latch

    if has_srclr { chip2.clear().expect("clear"); }                                 // Pulse SRCLR, () → Result<(), E>
                                                                                    // clears the shift register only; outputs unaffected until next RCK pulse
    if has_g {
        chip2.set_output_enable(false).expect("set_output_enable false");           // Force every output off via G, (enabled=false) → Result<(), E>
                                                                                    // drives G HIGH, blanking outputs without disturbing the shadow register
        chip2.set_output_enable(true).expect("set_output_enable true");             // Re-enable outputs, (enabled=true) → Result<(), E>
                                                                                    // drives G LOW; outputs resume from the previously-latched state
    }
}
