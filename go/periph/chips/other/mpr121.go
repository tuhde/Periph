// Package other contains drivers for miscellaneous peripheral chips
// (MPR121, etc.) over I²C.
package other

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MPR121 register addresses.
const (
	mpr121RegELE0_7Touch  uint8 = 0x00
	mpr121RegELE8_ProxTch uint8 = 0x01
	mpr121RegELE0_7OOR    uint8 = 0x02
	mpr121RegMHDR        uint8 = 0x2B
	mpr121RegNHDR        uint8 = 0x2C
	mpr121RegMHDF        uint8 = 0x2F
	mpr121RegNHDF        uint8 = 0x30
	mpr121RegE0TTH       uint8 = 0x41
	mpr121RegE0RTH       uint8 = 0x42
	mpr121RegEProxTTH    uint8 = 0x59
	mpr121RegEProxRTH    uint8 = 0x5A
	mpr121RegDebounce    uint8 = 0x5B
	mpr121RegCDCConfig   uint8 = 0x5C
	mpr121RegCDTConfig   uint8 = 0x5D
	mpr121RegECR         uint8 = 0x5E
	mpr121RegAutoconfig0 uint8 = 0x7B
	mpr121RegAutoconfig1 uint8 = 0x7C
	mpr121RegUSL         uint8 = 0x7D
	mpr121RegLSL         uint8 = 0x7E
	mpr121RegTL          uint8 = 0x7F
	mpr121RegSRST        uint8 = 0x80

	mpr121SoftResetKey      uint8 = 0x63
	mpr121TouchDefault      uint8 = 12
	mpr121ReleaseDefault    uint8 = 6
	mpr121CDCConfigDefault  uint8 = 0x10
	mpr121CDTConfigDefault  uint8 = 0x24
	mpr121Autoconfig0Default uint8 = 0x0B
	mpr121ECRDefault        uint8 = 0x8C
	mpr121USL3V3            uint8 = 0xC9
	mpr121TL3V3             uint8 = 0xB4
	mpr121LSL3V3            uint8 = 0x82
)

// MPR121Minimal is the MPR121 capacitive touch sensor controller — minimal interface.
//
// Provides 12-electrode touch/release detection with no configuration
// beyond the connection. Performs soft reset, applies default
// touch/release thresholds, enables the chip's automatic CDC/CDT
// configuration, and enters Run Mode on all 12 electrodes at
// construction.
//
// Default I²C address: 0x5A (selects via ADDR pin: 0x5A/0x5B/0x5C/0x5D).
type MPR121Minimal struct {
	connection connection.Connection
	addr       uint8
}

// NewMPR121Minimal creates a new MPR121Minimal and performs the default
// initialization sequence: soft reset, write defaults, enter Run Mode.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (typically 0x5A).
func NewMPR121Minimal(conn connection.Connection) (*MPR121Minimal, error) {
	d := &MPR121Minimal{connection: conn, addr: 0x5A}
	if err := d.softReset(); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegMHDR, 0x01); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegNHDR, 0x01); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegMHDF, 0x01); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegNHDF, 0x01); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegCDCConfig, mpr121CDCConfigDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegCDTConfig, mpr121CDTConfigDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegUSL, mpr121USL3V3); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegTL, mpr121TL3V3); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegLSL, mpr121LSL3V3); err != nil {
		return nil, err
	}
	if err := d.writeReg(mpr121RegAutoconfig0, mpr121Autoconfig0Default); err != nil {
		return nil, err
	}
	for n := uint8(0); n < 12; n++ {
		if err := d.writeReg(mpr121RegE0TTH+2*n, mpr121TouchDefault); err != nil {
			return nil, err
		}
		if err := d.writeReg(mpr121RegE0RTH+2*n, mpr121ReleaseDefault); err != nil {
			return nil, err
		}
	}
	if err := d.writeReg(mpr121RegECR, mpr121ECRDefault); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *MPR121Minimal) softReset() error {
	if err := d.writeReg(mpr121RegSRST, mpr121SoftResetKey); err != nil {
		return err
	}
	time.Sleep(1 * time.Millisecond)
	return nil
}

func (d *MPR121Minimal) writeReg(reg, value uint8) error {
	return d.connection.Write([]byte{reg, value})
}

func (d *MPR121Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *MPR121Minimal) readReg16(reg uint8) (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | ((uint16(buf[1]) & 0x03) << 8), nil
}

// Touched reads the 12-bit electrode touch bitmask.
//
// Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte snapshot
// from register 0x00; ELEPROX is masked out.
//
// Returns the 12-bit bitmask; bit n = 1 if ELEn is currently touched.
func (d *MPR121Minimal) Touched() (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{mpr121RegELE0_7Touch}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | ((uint16(buf[1]) & 0x0F) << 8), nil
}

// IsTouched checks whether a single electrode is currently touched.
//
// electrode must be 0-11.
func (d *MPR121Minimal) IsTouched(electrode uint8) (bool, error) {
	if electrode >= 12 {
		return false, &mpr121Error{"electrode must be in 0..11"}
	}
	t, err := d.Touched()
	if err != nil {
		return false, err
	}
	return (t & (1 << electrode)) != 0, nil
}

type mpr121Error struct{ msg string }

func (e *mpr121Error) Error() string { return "mpr121: " + e.msg }

// MPR121Full is the MPR121 full driver — extends MPR121Minimal with
// configuration methods, filtered/baseline access, and interrupt
// helpers.
type MPR121Full struct {
	MPR121Minimal
}

const (
	// SOURCE_OOR is the AUTOCONFIG1 OORIE interrupt source (bit 2 of 0x7C).
	SOURCE_OOR uint8 = 0x04
	// SOURCE_ARF is the AUTOCONFIG1 ARFIE interrupt source (bit 1 of 0x7C).
	SOURCE_ARF uint8 = 0x02
	// SOURCE_ACF is the AUTOCONFIG1 ACFIE interrupt source (bit 0 of 0x7C).
	SOURCE_ACF uint8 = 0x01
)

// NewMPR121Full creates a new MPR121Full with the same initialisation as
// NewMPR121Minimal.
func NewMPR121Full(conn connection.Connection) (*MPR121Full, error) {
	m, err := NewMPR121Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &MPR121Full{*m}, nil
}

// Reset software-resets the chip and re-applies Minimal defaults.
func (d *MPR121Full) Reset() error {
	if err := d.softReset(); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegMHDR, 0x01); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegNHDR, 0x01); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegMHDF, 0x01); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegNHDF, 0x01); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegCDCConfig, mpr121CDCConfigDefault); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegCDTConfig, mpr121CDTConfigDefault); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegUSL, mpr121USL3V3); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegTL, mpr121TL3V3); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegLSL, mpr121LSL3V3); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegAutoconfig0, mpr121Autoconfig0Default); err != nil {
		return err
	}
	for n := uint8(0); n < 12; n++ {
		if err := d.writeReg(mpr121RegE0TTH+2*n, mpr121TouchDefault); err != nil {
			return err
		}
		if err := d.writeReg(mpr121RegE0RTH+2*n, mpr121ReleaseDefault); err != nil {
			return err
		}
	}
	return d.writeReg(mpr121RegECR, mpr121ECRDefault)
}

// Stop enters Stop Mode (ECR=0x00).
func (d *MPR121Full) Stop() error {
	return d.writeReg(mpr121RegECR, 0x00)
}

// Start enters Run Mode with the given electrode configuration.
//
// nElectrodes is 1-12, cl is 0-3, eleproxEn is 0-3.
func (d *MPR121Full) Start(nElectrodes, cl, eleproxEn uint8) error {
	if nElectrodes < 1 || nElectrodes > 12 {
		return &mpr121Error{"n_electrodes must be in 1..12"}
	}
	if cl > 3 {
		return &mpr121Error{"cl must be in 0..3"}
	}
	if eleproxEn > 3 {
		return &mpr121Error{"eleprox_en must be in 0..3"}
	}
	ecr := ((cl & 0x03) << 6) | ((eleproxEn & 0x03) << 4) | (nElectrodes & 0x0F)
	return d.writeReg(mpr121RegECR, ecr)
}

// ConfigureThresholds sets touch and release thresholds for a single electrode.
func (d *MPR121Full) ConfigureThresholds(electrode, touch, release uint8) error {
	if electrode >= 12 {
		return &mpr121Error{"electrode must be in 0..11"}
	}
	if err := d.writeReg(mpr121RegE0TTH+2*electrode, touch); err != nil {
		return err
	}
	return d.writeReg(mpr121RegE0RTH+2*electrode, release)
}

// ConfigureAllThresholds applies the same touch and release thresholds to all 12 electrodes.
func (d *MPR121Full) ConfigureAllThresholds(touch, release uint8) error {
	for n := uint8(0); n < 12; n++ {
		if err := d.ConfigureThresholds(n, touch, release); err != nil {
			return err
		}
	}
	return nil
}

// ConfigureProximityThresholds sets ELEPROX touch and release thresholds.
func (d *MPR121Full) ConfigureProximityThresholds(touch, release uint8) error {
	if err := d.writeReg(mpr121RegEProxTTH, touch); err != nil {
		return err
	}
	return d.writeReg(mpr121RegEProxRTH, release)
}

// Filtered reads the 10-bit filtered capacitance data for an electrode.
//
// electrode is 0-11 for ELE0-ELE11, 12 for ELEPROX.
func (d *MPR121Full) Filtered(electrode uint8) (uint16, error) {
	if electrode > 12 {
		return 0, &mpr121Error{"electrode must be in 0..12"}
	}
	addr := uint8(0x04 + 2*electrode)
	if electrode == 12 {
		addr = 0x1C
	}
	return d.readReg16(addr)
}

// Baseline reads the 10-bit baseline for an electrode.
//
// The chip stores only the 8 MSBs of the baseline; the returned value is
// shifted left by 2 to align with Filtered.
func (d *MPR121Full) Baseline(electrode uint8) (uint16, error) {
	if electrode > 12 {
		return 0, &mpr121Error{"electrode must be in 0..12"}
	}
	addr := uint8(0x1E + electrode)
	if electrode == 12 {
		addr = 0x2A
	}
	v, err := d.readReg(addr)
	if err != nil {
		return 0, err
	}
	return uint16(v) << 2, nil
}

// SetBaseline writes a baseline value (Stop Mode only).
//
// value is 10-bit; only the 8 MSBs are stored.
func (d *MPR121Full) SetBaseline(electrode uint8, value uint16) error {
	if electrode > 12 {
		return &mpr121Error{"electrode must be in 0..12"}
	}
	addr := uint8(0x1E + electrode)
	if electrode == 12 {
		addr = 0x2A
	}
	return d.writeReg(addr, uint8(value>>2))
}

// OORStatus reads the 13-bit out-of-range bitmask.
func (d *MPR121Full) OORStatus() (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{mpr121RegELE0_7OOR}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | ((uint16(buf[1]) & 0x1F) << 8), nil
}

// ConfigureBaselineFilter sets the global baseline filter parameters (Stop Mode).
func (d *MPR121Full) ConfigureBaselineFilter(mhdr, nhdr, nclr, fdlr,
	mhdf, nhdf, nclf, fdlf,
	nhdt, nclt, fdlt uint8) error {
	if err := d.writeReg(mpr121RegMHDR, mhdr&0x3F); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegNHDR, nhdr&0x3F); err != nil {
		return err
	}
	if err := d.writeReg(0x2D, nclr); err != nil {
		return err
	}
	if err := d.writeReg(0x2E, fdlr); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegMHDF, mhdf&0x3F); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegNHDF, nhdf&0x3F); err != nil {
		return err
	}
	if err := d.writeReg(0x31, nclf); err != nil {
		return err
	}
	if err := d.writeReg(0x32, fdlf); err != nil {
		return err
	}
	if err := d.writeReg(0x33, nhdt&0x3F); err != nil {
		return err
	}
	if err := d.writeReg(0x34, nclt); err != nil {
		return err
	}
	return d.writeReg(0x35, fdlt)
}

// ConfigureSampling sets global AFE (sampling) configuration (Stop Mode).
func (d *MPR121Full) ConfigureSampling(cdc, cdt, ffi, sfi, esi uint8) error {
	cdcCfg := ((ffi & 0x03) << 6) | (cdc & 0x3F)
	cdtCfg := ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07)
	if err := d.writeReg(mpr121RegCDCConfig, cdcCfg); err != nil {
		return err
	}
	return d.writeReg(mpr121RegCDTConfig, cdtCfg)
}

// ConfigureDebounce sets debounce counts (Stop Mode).
func (d *MPR121Full) ConfigureDebounce(touch, release uint8) error {
	deb := ((release & 0x07) << 4) | (touch & 0x07)
	return d.writeReg(mpr121RegDebounce, deb)
}

// ConfigureAutoconfig computes USL/TL/LSL from VDD and writes autoconfig registers (Stop Mode).
func (d *MPR121Full) ConfigureAutoconfig(vddMv uint16, retry uint8, scts, are, ace bool) error {
	usl := uint8(((vddMv - 700) * 256) / vddMv)
	tl := uint8(float64(usl) * 0.9)
	lsl := uint8(float64(usl) * 0.65)
	if err := d.writeReg(mpr121RegUSL, usl); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegTL, tl); err != nil {
		return err
	}
	if err := d.writeReg(mpr121RegLSL, lsl); err != nil {
		return err
	}
	cdcCfg, err := d.readReg(mpr121RegCDCConfig)
	if err != nil {
		return err
	}
	ffi := (cdcCfg >> 6) & 0x03
	var autoconfig0 uint8 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4)
	if are {
		autoconfig0 |= 0x08
	}
	if ace {
		autoconfig0 |= 0x01
	}
	if err := d.writeReg(mpr121RegAutoconfig0, autoconfig0); err != nil {
		return err
	}
	var autoconfig1 uint8
	if scts {
		autoconfig1 = 0x80
	}
	return d.writeReg(mpr121RegAutoconfig1, autoconfig1)
}

// ProximityTouched returns true if the ELEPROX virtual electrode is touched.
func (d *MPR121Full) ProximityTouched() (bool, error) {
	v, err := d.readReg(mpr121RegELE8_ProxTch)
	if err != nil {
		return false, err
	}
	return (v & 0x10) != 0, nil
}

// ClearOvercurrent clears the OVCF bit in register 0x01.
func (d *MPR121Full) ClearOvercurrent() error {
	v, err := d.readReg(mpr121RegELE8_ProxTch)
	if err != nil {
		return err
	}
	return d.writeReg(mpr121RegELE8_ProxTch, v&0x7F)
}

// EnableInterrupt enables one of the AUTOCONFIG1-based interrupt sources.
func (d *MPR121Full) EnableInterrupt(source uint8) error {
	cur, err := d.readReg(mpr121RegAutoconfig1)
	if err != nil {
		return err
	}
	return d.writeReg(mpr121RegAutoconfig1, cur|(source&0x07))
}

// DisableInterrupt disables one of the AUTOCONFIG1-based interrupt sources.
func (d *MPR121Full) DisableInterrupt(source uint8) error {
	cur, err := d.readReg(mpr121RegAutoconfig1)
	if err != nil {
		return err
	}
	return d.writeReg(mpr121RegAutoconfig1, cur & ^(source&0x07))
}
