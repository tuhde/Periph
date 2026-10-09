use std::collections::{BTreeMap, BTreeSet};

use embedded_hal::i2c::{ErrorKind, ErrorType, NoAcknowledgeSource, Operation};

use super::*;

#[derive(Debug, Clone, Copy)]
struct FakeError {
    kind: ErrorKind,
    #[allow(dead_code)]
    text: &'static str,
}

impl embedded_hal::i2c::Error for FakeError {
    fn kind(&self) -> ErrorKind {
        self.kind
    }
}

const NACK: FakeError = FakeError { kind: ErrorKind::NoAcknowledge(NoAcknowledgeSource::Address), text: "ENXIO" };
const BUSY: FakeError = FakeError { kind: ErrorKind::Bus, text: "EBUSY" };
const IO: FakeError = FakeError { kind: ErrorKind::Bus, text: "EIO" };

/// Fake bus: devices maps address -> { register: byte } (missing registers read 0xFF).
#[derive(Default)]
struct FakeI2c {
    devices: BTreeMap<u8, BTreeMap<u32, u8>>,
    busy: BTreeSet<u8>,
    no_zero_length: bool,
    broken: bool,
    read_error: bool,
    probes: Vec<(&'static str, u8)>,
    writes: Vec<(u8, Vec<u8>)>,
}

impl FakeI2c {
    fn with(devices: &[(u8, &[(u32, u8)])]) -> Self {
        let mut f = FakeI2c::default();
        for (addr, regs) in devices {
            f.devices.insert(*addr, regs.iter().copied().collect());
        }
        f
    }

    fn probe(&mut self, kind: &'static str, addr: u8) -> Result<(), FakeError> {
        self.probes.push((kind, addr));
        if self.broken {
            Err(IO)
        } else if self.busy.contains(&addr) {
            Err(BUSY)
        } else if !self.devices.contains_key(&addr) {
            Err(NACK)
        } else {
            Ok(())
        }
    }
}

impl ErrorType for FakeI2c {
    type Error = FakeError;
}

impl embedded_hal::i2c::I2c for FakeI2c {
    fn transaction(&mut self, address: u8, operations: &mut [Operation<'_>]) -> Result<(), Self::Error> {
        match operations {
            [Operation::Write(w)] if w.is_empty() => {
                if self.no_zero_length {
                    return Err(IO);
                }
                self.probe("quick", address)
            }
            [Operation::Read(r)] => {
                self.probe("read", address)?;
                r.fill(0);
                Ok(())
            }
            [Operation::Write(w), Operation::Read(r)] => {
                if self.read_error {
                    return Err(IO);
                }
                let regs = self.devices.get(&address).ok_or(NACK)?;
                self.writes.push((address, w.to_vec()));
                let reg = w.iter().fold(0u32, |a, b| (a << 8) | *b as u32);
                for (i, byte) in r.iter_mut().enumerate() {
                    *byte = *regs.get(&(reg + i as u32)).unwrap_or(&0xFF);
                }
                Ok(())
            }
            _ => unreachable!("unexpected operation shape in test fake"),
        }
    }
}

fn only(bus: &mut FakeI2c, active: bool) -> DiscoveredDevice {
    discover(bus, active).unwrap().remove(0)
}

fn one(addr: u8, regs: &[(u32, u8)]) -> DiscoveredDevice {
    only(&mut FakeI2c::with(&[(addr, regs)]), false)
}

/// Like `one`, but with active probing — needed at 0x28-0x2F, which also hosts the
/// write-sensitive DS1881.
fn one_active(addr: u8, regs: &[(u32, u8)]) -> DiscoveredDevice {
    only(&mut FakeI2c::with(&[(addr, regs)]), true)
}

#[test]
fn scan_finds_all_and_picks_probe_method() {
    let mut bus = FakeI2c::with(&[(0x76, &[]), (0x50, &[]), (0x1B, &[])]);
    assert_eq!(scan(&mut bus).unwrap(), vec![0x1B, 0x50, 0x76]);
    let kind = |a: u8| bus.probes.iter().find(|p| p.1 == a).unwrap().0;
    assert_eq!(kind(0x76), "quick");
    assert_eq!(kind(0x08), "quick");
    assert_eq!(kind(0x50), "read");
    assert_eq!(kind(0x30), "read");
    assert_eq!(kind(0x5F), "read");
    assert_eq!(bus.probes.iter().map(|p| p.1).min(), Some(0x08));
    assert_eq!(bus.probes.iter().map(|p| p.1).max(), Some(0x77));
}

#[test]
fn scan_falls_back_to_read_byte_when_zero_length_write_fails() {
    let mut bus = FakeI2c::with(&[(0x76, &[])]);
    bus.no_zero_length = true;
    assert_eq!(scan(&mut bus).unwrap(), vec![0x76]);
    assert!(bus.probes.iter().all(|p| p.0 == "read"));
}

#[test]
fn scan_ebusy_is_present_and_flagged() {
    let mut bus = FakeI2c::with(&[(0x40, &[])]);
    bus.busy.insert(0x42);
    let found = scan_detailed(&mut bus, FIRST_ADDRESS, LAST_ADDRESS).unwrap();
    assert_eq!(found.get(&0x40), Some(&false));
    assert_eq!(found.get(&0x42), Some(&true));
    assert_eq!(found.len(), 2);
}

#[test]
fn scan_errors_when_every_address_fails() {
    let mut bus = FakeI2c::default();
    bus.broken = true;
    assert!(scan(&mut bus).is_err());
    assert_eq!(scan(&mut FakeI2c::default()).unwrap(), Vec::<u8>::new());
}

#[test]
fn identifies_chips_by_single_byte_register() {
    let table: [(&str, u32, u8, u8); 11] = [
        ("bme280", 0xD0, 0x60, 0x76), ("bmp280", 0xD0, 0x58, 0x76), ("bme680", 0xD0, 0x61, 0x77),
        ("bmp384", 0x00, 0x50, 0x76), ("mpu6050", 0x75, 0x68, 0x68), ("mpu9250", 0x75, 0x71, 0x68),
        ("mpu9255", 0x75, 0x73, 0x69), ("l3g4200d", 0x0F, 0xD3, 0x68), ("lps33hw", 0x0F, 0xB1, 0x5C),
        ("adxl345", 0x00, 0xE5, 0x53), ("vl53l0x", 0xC0, 0xEE, 0x29),
    ];
    for (id, reg, value, addr) in table {
        let d = one_active(addr, &[(reg, value)]);
        assert_eq!(d.identified, Some(id), "{id}");
    }
    assert_eq!(one_active(0x28, &[(0x37, 0x92)]).identified, Some("mfrc522"));
    assert_eq!(one(0x76, &[(0xD0, 0x60)]).driver, Some("bme280"));
}

#[test]
fn identifies_multi_byte_and_masked_identity_registers() {
    assert_eq!(one(0x52, &[(0x00, 0x60), (0x01, 0x01)]).identified, Some("ens160"));
    assert_eq!(one(0x40, &[(0xFF, 0x22), (0x100, 0x60)]).identified, Some("ina226"));
    assert_eq!(one(0x40, &[(0xFF, 0x32), (0x100, 0x20)]).identified, Some("ina3221"));
    assert_eq!(one(0x18, &[(0x07, 0x04), (0x08, 0x01)]).identified, Some("mcp9808"));
    let mut bus = FakeI2c::with(&[(0x29, &[(0x010F, 0xEA), (0x0110, 0xCC)])]);
    assert_eq!(only(&mut bus, true).identified, Some("vl53l1x"));
    assert!(bus.writes.contains(&(0x29, vec![0x01, 0x0F])));
}

#[test]
fn ds1881_write_sensitive_blocks_probe_at_0x29() {
    let mut bus = FakeI2c::with(&[(0x29, &[(0xC0, 0xEE)])]);
    let d = only(&mut bus, false);
    assert_eq!(d.identified, None);
    assert_eq!(d.probe_skipped_reason, Some(ProbeSkipReason::WriteSensitiveCandidate));
    assert!(d.candidates.contains(&"ds1881"));
    assert!(bus.writes.is_empty());
}

#[test]
fn hmc5883l_and_lsm303_mag_are_ambiguous() {
    // Both report the identity 0x483433 (registry/known_ambiguities.json).
    let d = one(0x1E, &[(0x0A, 0x48), (0x0B, 0x34), (0x0C, 0x33)]);
    assert_eq!(d.identified, None);
    assert_eq!(d.candidates, vec!["hmc5883l", "lsm303-mag"]);
}

#[test]
fn write_sensitive_candidates_block_probing_unless_active() {
    let regs: &[(u32, u8)] = &[(0x0F, 0x01), (0x10, 0x17)];
    let mut bus = FakeI2c::with(&[(0x48, regs)]);
    let d = only(&mut bus, false);
    assert_eq!(d.identified, None);
    assert_eq!(d.probe_skipped_reason, Some(ProbeSkipReason::WriteSensitiveCandidate));
    assert!(bus.writes.is_empty());
    assert_eq!(only(&mut FakeI2c::with(&[(0x48, regs)]), true).identified, Some("tmp117"));

    assert_eq!(one(0x39, &[(0x92, 0xAB)]).probe_skipped_reason, Some(ProbeSkipReason::WriteSensitiveCandidate));
    assert_eq!(only(&mut FakeI2c::with(&[(0x39, &[(0x92, 0xAB)])]), true).identified, Some("apds9960"));
    assert_eq!(only(&mut FakeI2c::with(&[(0x39, &[(0x92, 0x39)])]), true).identified, Some("apds-9930"));
}

#[test]
fn ambiguity_is_a_final_answer() {
    let d = one(0x5C, &[(0x0F, 0xB4)]);
    assert_eq!(d.identified, None);
    assert_eq!(d.candidates, vec!["lps22df", "lps28dfw"]);
    let d = one(0x77, &[(0xD0, 0x55)]);
    assert_eq!((d.identified, d.candidates), (None, vec!["bmp085", "bmp180"]));
}

#[test]
fn falls_back_to_id_less_candidates() {
    assert_eq!(one(0x68, &[]).candidates, vec!["drv8830", "ds3231", "pcf8523"]);
    assert_eq!(one(0x40, &[]).candidates, vec!["ina219"]);
    let d = one(0x36, &[]);
    assert_eq!((d.identified, d.candidates), (None, vec!["as5600"]));
    let d = one(0x0B, &[]);
    assert!(d.candidates.is_empty() && d.identified.is_none());
    let mut bus = FakeI2c::with(&[(0x38, &[])]);
    let d = only(&mut bus, false);
    assert_eq!(d.candidates, vec!["ade7953", "aht21", "bma150", "pcf8574", "pcf8576"]);
    assert_eq!(d.probe_skipped_reason, Some(ProbeSkipReason::WriteSensitiveCandidate));
    assert!(bus.writes.is_empty());
}

#[test]
fn custom_registry_and_active_flag() {
    static EXPECTED: [u32; 1] = [0x42];
    static REG: [ChipEntry; 2] = [
        ChipEntry { id: "pcf-like", driver: None, write_sensitive: true, aliased: false, addresses: &[0x20], probe: None },
        ChipEntry {
            id: "idchip", driver: None, write_sensitive: false, aliased: false, addresses: &[0x20],
            probe: Some(IdProbe { register: 0x10, reg_bytes: 1, length: 1, little_endian: false, mask: 0xFF, expected: &EXPECTED }),
        },
    ];
    let mut bus = FakeI2c::with(&[(0x20, &[(0x10, 0x42)])]);
    let d = discover_with(&mut bus, &REG, false).unwrap().remove(0);
    assert_eq!(d.probe_skipped_reason, Some(ProbeSkipReason::WriteSensitiveCandidate));
    assert_eq!(d.candidates, vec!["idchip", "pcf-like"]);
    assert!(bus.writes.is_empty());
    let mut bus = FakeI2c::with(&[(0x20, &[(0x10, 0x42)])]);
    let d = discover_with(&mut bus, &REG, true).unwrap().remove(0);
    assert_eq!(d.identified, Some("idchip"));
    assert!(!bus.writes.is_empty());
}

#[test]
fn kernel_bound_address_is_reported_not_probed() {
    let mut bus = FakeI2c::with(&[(0x76, &[(0xD0, 0x60)])]);
    bus.busy.insert(0x77);
    let devs = discover(&mut bus, false).unwrap();
    let d = devs.iter().find(|d| d.address == 0x77).unwrap();
    assert!(d.in_use_by_kernel);
    assert_eq!(d.probe_skipped_reason, Some(ProbeSkipReason::KernelBound));
    assert_eq!(d.identified, None);
    assert!(bus.writes.iter().all(|w| w.0 != 0x77));
}

#[test]
fn failed_identity_read_is_no_match() {
    let mut bus = FakeI2c::with(&[(0x76, &[(0xD0, 0x60)])]);
    bus.read_error = true;
    let d = only(&mut bus, false);
    assert_eq!(d.identified, None);
    assert!(d.candidates.is_empty());
}

#[test]
fn aliased_24aa02uid_block_is_merged_only_when_complete() {
    let all: Vec<(u8, &[(u32, u8)])> = (0x50u8..=0x57).map(|a| (a, &[][..])).collect();
    let devs = discover(&mut FakeI2c::with(&all), false).unwrap();
    assert_eq!(devs.len(), 1);
    assert_eq!((devs[0].address, devs[0].candidates.clone()), (0x50, vec!["24aa025uid", "24aa02uid", "mb85rc"]));
    assert_eq!(devs[0].aliases, (0x51u8..=0x57).collect::<Vec<_>>());
    let devs = discover(&mut FakeI2c::with(&[(0x50, &[]), (0x51, &[])]), false).unwrap();
    assert_eq!(devs.iter().map(|d| d.address).collect::<Vec<_>>(), vec![0x50, 0x51]);
    assert!(devs.iter().all(|d| d.aliases.is_empty()));
}

#[test]
fn registry_is_sane() {
    assert!(CHIPS.len() >= 45);
    let ids: BTreeSet<_> = CHIPS.iter().map(|c| c.id).collect();
    assert_eq!(ids.len(), CHIPS.len());
}
