// Package light contains drivers for Light, color, and proximity
// sensors (APDS-9930, etc.) over I²C.
package light

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// APDS-9930 register addresses.
const (
	apds9930RegENABLE   uint8 = 0x00
	apds9930RegATIME    uint8 = 0x01
	apds9930RegPTIME    uint8 = 0x02
	apds9930RegWTIME    uint8 = 0x03
	apds9930RegAILTL    uint8 = 0x04
	apds9930RegAILTH    uint8 = 0x05
	apds9930RegAIHTL    uint8 = 0x06
	apds9930RegAIHTH    uint8 = 0x07
	apds9930RegPILTL    uint8 = 0x08
	apds9930RegPILTH    uint8 = 0x09
	apds9930RegPIHTL    uint8 = 0x0A
	apds9930RegPIHTH    uint8 = 0x0B
	apds9930RegPERS     uint8 = 0x0C
	apds9930RegCONFIG   uint8 = 0x0D
	apds9930RegPPULSE   uint8 = 0x0E
	apds9930RegCONTROL  uint8 = 0x0F
	apds9930RegID       uint8 = 0x12
	apds9930RegSTATUS   uint8 = 0x13
	apds9930RegCH0DATAL uint8 = 0x14
	apds9930RegCH1DATAL uint8 = 0x16
	apds9930RegPDATAL   uint8 = 0x18
	apds9930RegPOFFSET  uint8 = 0x1E

	apds9930CFNClearProximity uint8 = 0x05
	apds9930CFNClearALS       uint8 = 0x06
	apds9930CFNClearBoth      uint8 = 0x07

	apds9930CMDWrite   uint8 = 0x80
	apds9930CMDRead    uint8 = 0xA0
	apds9930CMDSpecial uint8 = 0xE0

	apds9930ATIMEDefault   uint8 = 0xDB
	apds9930PTIMEDefault   uint8 = 0xFF
	apds9930PPULSEDefault  uint8 = 0x08
	apds9930CONTROLDefault uint8 = 0x20
	apds9930ENABLEDefault  uint8 = 0x07

	apds9930IDExpected uint8 = 0x39
)

func apds9930CmdWrite(reg uint8) uint8   { return apds9930CMDWrite | (reg & 0x1F) }
func apds9930CmdRead(reg uint8) uint8    { return apds9930CMDRead | (reg & 0x1F) }
func apds9930CmdSpecial(f uint8) uint8   { return apds9930CMDSpecial | (f & 0x1F) }

func apds9930AgainFactor(againIdx uint8, agl bool) float32 {
	if !agl {
		switch againIdx & 0x03 {
		case 0:
			return 1.0
		case 1:
			return 8.0
		case 2:
			return 16.0
		default:
			return 120.0
		}
	}
	switch againIdx & 0x03 {
	case 0:
		return 1.0 / 6.0
	case 1:
		return 8.0 / 6.0
	case 2:
		return 16.0 / 6.0
	default:
		return 20.0
	}
}

func apds9930EncodeOffset(v int8) uint8 {
	if v >= 0 {
		return 0x80 | (uint8(v) & 0x7F)
	}
	return uint8(-v) & 0x7F
}

// APDS9930Minimal is the APDS-9930 digital ambient light and proximity
// sensor driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz Fast mode. The ALS and proximity
// engines are enabled at construction with sensible defaults.
//
// Default I²C address: 0x39 (fixed).
//
// Configuration defaults:
//   - ATIME   = 0xDB (101 ms integration — rejects 50/60 Hz fluorescent flicker)
//   - PTIME   = 0xFF (2.73 ms proximity ADC time, datasheet default)
//   - PPULSE  = 0x08 (8 LED pulses — factory-calibrated for 100 mm range)
//   - CONTROL = 0x20 (PDIODE=Ch1, PDRIVE=100 mA, PGAIN=1x, AGAIN=1x)
//   - ENABLE  = 0x07 (PON + AEN + PEN; wait timer and interrupts disabled)
type APDS9930Minimal struct {
	connection connection.Connection
	addr       uint8
}

// NewAPDS9930Minimal creates a new APDS9930Minimal and performs the
// mandatory initialisation sequence: 6 ms power-up wait, ID check,
// ENABLE=0, set ATIME/PTIME/PPULSE/CONTROL, ENABLE=0x07, 12 ms
// first-conversion wait.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x39, fixed).
func NewAPDS9930Minimal(conn connection.Connection) (*APDS9930Minimal, error) {
	d := &APDS9930Minimal{connection: conn, addr: 0x39}
	time.Sleep(6 * time.Millisecond)
	id, err := d.readReg(apds9930RegID)
	if err != nil {
		return nil, err
	}
	if id != apds9930IDExpected {
		return nil, &apds9930IDError{got: id}
	}
	if err := d.writeReg(apds9930RegENABLE, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(apds9930RegATIME, apds9930ATIMEDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(apds9930RegPTIME, apds9930PTIMEDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(apds9930RegPPULSE, apds9930PPULSEDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(apds9930RegCONTROL, apds9930CONTROLDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(apds9930RegENABLE, apds9930ENABLEDefault); err != nil {
		return nil, err
	}
	time.Sleep(12 * time.Millisecond)
	return d, nil
}

// apds9930IDError is returned by NewAPDS9930Minimal when the device ID
// register does not match the expected 0x39.
type apds9930IDError struct {
	got uint8
}

func (e *apds9930IDError) Error() string {
	return "apds9930: chip not found (ID=0x" + apdsHexByte(e.got) + ", expected 0x39)"
}

func apdsHexByte(b uint8) string {
	const hex = "0123456789ABCDEF"
	return string([]byte{hex[(b>>4)&0xF], hex[b&0xF]})
}

func (d *APDS9930Minimal) writeReg(reg, value uint8) error {
	return d.connection.Write([]byte{apds9930CmdWrite(reg), value})
}

func (d *APDS9930Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.connection.WriteRead([]byte{apds9930CmdRead(reg)}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *APDS9930Minimal) readReg16(reg uint8) (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{apds9930CmdRead(reg)}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(buf[1]) << 8) | uint16(buf[0]), nil
}

func (d *APDS9930Minimal) special(f uint8) error {
	return d.connection.Write([]byte{apds9930CmdSpecial(f)})
}

// ChipID returns the device ID register (expect 0x39).
func (d *APDS9930Minimal) ChipID() (uint8, error) {
	return d.readReg(apds9930RegID)
}

// Lux reads the ambient illuminance using Ch0 (visible + IR) and Ch1
// (IR-only) with the open-air lux coefficients from the datasheet.
//
// Returns illuminance in lux.
func (d *APDS9930Minimal) Lux() (float64, error) {
	ch0, err := d.readReg16(apds9930RegCH0DATAL)
	if err != nil {
		return 0, err
	}
	ch1, err := d.readReg16(apds9930RegCH1DATAL)
	if err != nil {
		return 0, err
	}
	ctrl, err := d.readReg(apds9930RegCONTROL)
	if err != nil {
		return 0, err
	}
	cfg, err := d.readReg(apds9930RegCONFIG)
	if err != nil {
		return 0, err
	}
	atime, err := d.readReg(apds9930RegATIME)
	if err != nil {
		return 0, err
	}
	alsitMs := 2.73 * (256.0 - float64(atime))
	againX := float64(apds9930AgainFactor(ctrl&0x03, cfg&0x04 != 0))
	iac1 := float64(ch0) - 1.862*float64(ch1)
	iac2 := 0.746*float64(ch0) - 1.291*float64(ch1)
	iac := iac1
	if iac2 > iac {
		iac = iac2
	}
	if iac < 0 {
		iac = 0
	}
	lpc := (0.49 * 52.0) / (alsitMs * againX)
	return iac * lpc, nil
}

// Proximity reads the proximity ADC count.
//
// Higher counts mean a closer object. Realistically limited to 10 bits
// (0-1023) at the default PTIME=0xFF (one ADC cycle).
//
// Returns the raw 16-bit proximity count.
func (d *APDS9930Minimal) Proximity() (uint16, error) {
	return d.readReg16(apds9930RegPDATAL)
}

// APDS9930Full is the APDS-9930 driver — full interface. Extends
// APDS9930Minimal with ALS/proximity configuration, raw channel reads,
// interrupt thresholds with persistence, status decoding,
// sleep-after-interrupt, and proximity offset compensation.
type APDS9930Full struct {
	*APDS9930Minimal
}

// NewAPDS9930Full creates a new APDS9930Full with the same initialisation
// as NewAPDS9930Minimal.
func NewAPDS9930Full(conn connection.Connection) (*APDS9930Full, error) {
	m, err := NewAPDS9930Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &APDS9930Full{APDS9930Minimal: m}, nil
}

// ConfigureALS sets ATIME and the AGAIN field of CONTROL (preserving
// PDRIVE/PDIODE/PGAIN). atime 0-255, again 0-3 (0=1x, 1=8x, 2=16x, 3=120x).
// agl=true enables the AGL divide-by-6 gain-level bit in CONFIG.
func (d *APDS9930Full) ConfigureALS(atime, again uint8, agl bool) error {
	if err := d.writeReg(apds9930RegATIME, atime); err != nil {
		return err
	}
	ctrl, err := d.readReg(apds9930RegCONTROL)
	if err != nil {
		return err
	}
	ctrl = (ctrl & 0xFC) | (again & 0x03)
	if err := d.writeReg(apds9930RegCONTROL, ctrl); err != nil {
		return err
	}
	cfg, err := d.readReg(apds9930RegCONFIG)
	if err != nil {
		return err
	}
	if agl {
		cfg |= 0x04
	} else {
		cfg &^= 0x04
	}
	cfg &^= 0x06
	return d.writeReg(apds9930RegCONFIG, cfg)
}

// ConfigureProximity sets the LED pulse count, gain, drive current, ADC
// integration time, and PDL flag.
func (d *APDS9930Full) ConfigureProximity(ppulse, pgain, pdrive uint8, pdl bool, ptime uint8) error {
	if err := d.writeReg(apds9930RegPPULSE, ppulse); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegPTIME, ptime); err != nil {
		return err
	}
	ctrl, err := d.readReg(apds9930RegCONTROL)
	if err != nil {
		return err
	}
	ctrl = (ctrl & 0x03) | ((pdrive & 0x03) << 6) | 0x20 | ((pgain & 0x03) << 2)
	if err := d.writeReg(apds9930RegCONTROL, ctrl); err != nil {
		return err
	}
	cfg, err := d.readReg(apds9930RegCONFIG)
	if err != nil {
		return err
	}
	if pdl {
		cfg |= 0x01
	} else {
		cfg &^= 0x01
	}
	cfg &^= 0x06
	return d.writeReg(apds9930RegCONFIG, cfg)
}

// ConfigureWait sets WTIME and the WLONG bit in CONFIG, then enables WEN.
func (d *APDS9930Full) ConfigureWait(wtime uint8, wlong bool) error {
	if err := d.writeReg(apds9930RegWTIME, wtime); err != nil {
		return err
	}
	cfg, err := d.readReg(apds9930RegCONFIG)
	if err != nil {
		return err
	}
	if wlong {
		cfg |= 0x02
	} else {
		cfg &^= 0x02
	}
	cfg &^= 0x04
	if err := d.writeReg(apds9930RegCONFIG, cfg); err != nil {
		return err
	}
	en, err := d.readReg(apds9930RegENABLE)
	if err != nil {
		return err
	}
	en |= 0x08
	return d.writeReg(apds9930RegENABLE, en)
}

// DisableWait clears WEN in ENABLE.
func (d *APDS9930Full) DisableWait() error {
	en, err := d.readReg(apds9930RegENABLE)
	if err != nil {
		return err
	}
	en &^= 0x08
	return d.writeReg(apds9930RegENABLE, en)
}

// Ch0 reads the raw Ch0 (visible + IR) ADC count.
func (d *APDS9930Full) Ch0() (uint16, error) {
	return d.readReg16(apds9930RegCH0DATAL)
}

// Ch1 reads the raw Ch1 (IR-only) ADC count.
func (d *APDS9930Full) Ch1() (uint16, error) {
	return d.readReg16(apds9930RegCH1DATAL)
}

// Status decodes the STATUS register into named boolean fields.
type APDS9930Status struct {
	AVALID bool
	PVALID bool
	PSAT   bool
	AINT   bool
	PINT   bool
}

// Status returns the STATUS register decoded into named fields.
func (d *APDS9930Full) Status() (APDS9930Status, error) {
	s, err := d.readReg(apds9930RegSTATUS)
	if err != nil {
		return APDS9930Status{}, err
	}
	return APDS9930Status{
		AVALID: s&0x01 != 0,
		PVALID: s&0x02 != 0,
		PSAT:   s&0x40 != 0,
		AINT:   s&0x10 != 0,
		PINT:   s&0x20 != 0,
	}, nil
}

// SetAlsThresholds sets ALS interrupt thresholds (raw Ch0 counts, not lux)
// and enables AIEN. persistence 0-15 (0=every, 1, 2, 3, 5, 10, 15, 20, 25,
// 30, 35, 40, 45, 50, 55, 60 consecutive out-of-range cycles).
func (d *APDS9930Full) SetAlsThresholds(low, high uint16, persistence uint8) error {
	if low > high {
		high = low
	}
	if err := d.writeReg(apds9930RegAILTL, uint8(low)); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegAILTH, uint8(low>>8)); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegAIHTL, uint8(high)); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegAIHTH, uint8(high>>8)); err != nil {
		return err
	}
	pers, err := d.readReg(apds9930RegPERS)
	if err != nil {
		return err
	}
	pers = (pers & 0xF0) | (persistence & 0x0F)
	if err := d.writeReg(apds9930RegPERS, pers); err != nil {
		return err
	}
	en, err := d.readReg(apds9930RegENABLE)
	if err != nil {
		return err
	}
	en |= 0x10
	return d.writeReg(apds9930RegENABLE, en)
}

// SetProximityThresholds sets proximity interrupt thresholds and enables
// PIEN. persistence 0-15 (0=every, 1-15=N consecutive).
func (d *APDS9930Full) SetProximityThresholds(low, high uint16, persistence uint8) error {
	if low > high {
		high = low
	}
	if err := d.writeReg(apds9930RegPILTL, uint8(low)); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegPILTH, uint8(low>>8)); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegPIHTL, uint8(high)); err != nil {
		return err
	}
	if err := d.writeReg(apds9930RegPIHTH, uint8(high>>8)); err != nil {
		return err
	}
	pers, err := d.readReg(apds9930RegPERS)
	if err != nil {
		return err
	}
	pers = (pers & 0x0F) | ((persistence & 0x0F) << 4)
	if err := d.writeReg(apds9930RegPERS, pers); err != nil {
		return err
	}
	en, err := d.readReg(apds9930RegENABLE)
	if err != nil {
		return err
	}
	en |= 0x20
	return d.writeReg(apds9930RegENABLE, en)
}

// ClearInterrupt issues the chip's special-function command to clear
// pending interrupts. channel: 0=both, 1=ALS, 2=proximity.
func (d *APDS9930Full) ClearInterrupt(channel uint8) error {
	var f uint8
	switch channel {
	case 1:
		f = apds9930CFNClearALS
	case 2:
		f = apds9930CFNClearProximity
	default:
		f = apds9930CFNClearBoth
	}
	return d.special(f)
}

// SetProximityOffset sets the POFFSET register (sign-magnitude).
// offset in -127..+127 (positive shifts data up).
func (d *APDS9930Full) SetProximityOffset(offset int8) error {
	return d.writeReg(apds9930RegPOFFSET, apds9930EncodeOffset(offset))
}

// SleepAfterInterrupt enables or disables SAI in ENABLE.
func (d *APDS9930Full) SleepAfterInterrupt(enable bool) error {
	en, err := d.readReg(apds9930RegENABLE)
	if err != nil {
		return err
	}
	if enable {
		en |= 0x40
	} else {
		en &^= 0x40
	}
	return d.writeReg(apds9930RegENABLE, en)
}