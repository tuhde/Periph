// Package magnetometer contains drivers for Magnetometers.
package magnetometer

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/transport"
)

// AS5600 register addresses.
const (
	as5600RegZMCO       uint8 = 0x00
	as5600RegZPosH      uint8 = 0x01
	as5600RegZPosL      uint8 = 0x02
	as5600RegMPosH      uint8 = 0x03
	as5600RegMPosL      uint8 = 0x04
	as5600RegMAngH      uint8 = 0x05
	as5600RegMAngL      uint8 = 0x06
	as5600RegConfH      uint8 = 0x07
	as5600RegConfL      uint8 = 0x08
	as5600RegStatus     uint8 = 0x0B
	as5600RegRawAngleH  uint8 = 0x0C
	as5600RegRawAngleL  uint8 = 0x0D
	as5600RegAngleH     uint8 = 0x0E
	as5600RegAngleL     uint8 = 0x0F
	as5600RegAGC        uint8 = 0x1A
	as5600RegMagnitudeH uint8 = 0x1B
	as5600RegMagnitudeL uint8 = 0x1C
	as5600RegBurn       uint8 = 0xFF
)

// STATUS register bit masks.
const (
	as5600StatusMD uint8 = 0x08
	as5600StatusML uint8 = 0x10
	as5600StatusMH uint8 = 0x20
)

// As5600 is the fixed 7-bit I²C address of the chip.
const As5600Addr uint8 = 0x36

// As5600Minimal is the 12-bit contactless rotary position sensor driver —
// minimal interface.
//
// Reads the absolute angle in degrees and the scaled 12-bit angle count, and
// verifies magnet presence at construction by reading the STATUS register.
// No CONF writes are issued; the factory default (CONF=0x0000, NOM mode,
// hysteresis off, analog output, 16x slow filter) is used as-is.
type As5600Minimal struct {
	transport transport.Transport
}

// NewAs5600Minimal creates a new As5600Minimal and verifies magnet presence.
//
// The constructor reads the STATUS register once; if MD=0 (magnet not
// detected) it returns an error because angle data would be invalid in that
// state.
//
// transport must be a configured I²C transport bound to address 0x36.
func NewAs5600Minimal(t transport.Transport) (*As5600Minimal, error) {
	d := &As5600Minimal{transport: t}
	status, err := d.readReg8(as5600RegStatus)
	if err != nil {
		return nil, err
	}
	if status&as5600StatusMD == 0 {
		return nil, fmt.Errorf("as5600: magnet not detected (MD=0)")
	}
	return d, nil
}

// readReg8 reads a single byte from the given register.
func (d *As5600Minimal) readReg8(reg uint8) (uint8, error) {
	buf, err := d.transport.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// readReg12 reads a 12-bit value (high byte first) from two consecutive
// registers, packing bits 11:8 in the high byte's lower 4 bits.
func (d *As5600Minimal) readReg12(regHi uint8) (uint16, error) {
	buf, err := d.transport.WriteRead([]byte{regHi}, 2)
	if err != nil {
		return 0, err
	}
	return (uint16(buf[0]&0x0F) << 8) | uint16(buf[1]), nil
}

// writeReg8 writes a single byte to the given register.
func (d *As5600Minimal) writeReg8(reg uint8, val uint8) error {
	return d.transport.Write([]byte{reg, val})
}

// writeReg12 writes a 12-bit value split across two registers (high byte first).
func (d *As5600Minimal) writeReg12(regHi, regLo uint8, val uint16) error {
	v := val & 0x0FFF
	return d.transport.Write([]byte{regHi, byte((v >> 8) & 0x0F), byte(v & 0xFF)})
}

// Angle reads the scaled absolute angle in degrees (0.0–360.0 exclusive).
//
// Reads the ANGLE register (0x0E-0x0F), which respects any OTP-programmed
// ZPOS/MPOS range.
func (d *As5600Minimal) Angle() (float64, error) {
	raw, err := d.angleRaw()
	if err != nil {
		return 0, err
	}
	return float64(raw) * 360.0 / 4096.0, nil
}

// AngleRaw reads the scaled 12-bit angle count (0–4095).
func (d *As5600Minimal) AngleRaw() (uint16, error) {
	return d.angleRaw()
}

func (d *As5600Minimal) angleRaw() (uint16, error) {
	return d.readReg12(as5600RegAngleH)
}

// IsMagnetDetected returns true if STATUS.MD=1 (Bz >= 8 mT).
func (d *As5600Minimal) IsMagnetDetected() (bool, error) {
	s, err := d.readReg8(as5600RegStatus)
	if err != nil {
		return false, err
	}
	return s&as5600StatusMD != 0, nil
}

// IsMagnetTooStrong returns true if STATUS.MH=1 (Bz > 90 mT).
func (d *As5600Minimal) IsMagnetTooStrong() (bool, error) {
	s, err := d.readReg8(as5600RegStatus)
	if err != nil {
		return false, err
	}
	return s&as5600StatusMH != 0, nil
}

// IsMagnetTooWeak returns true if STATUS.ML=1 (Bz < 30 mT).
func (d *As5600Minimal) IsMagnetTooWeak() (bool, error) {
	s, err := d.readReg8(as5600RegStatus)
	if err != nil {
		return false, err
	}
	return s&as5600StatusML != 0, nil
}

// As5600Full is the 12-bit contactless rotary position sensor driver — full
// interface. Extends As5600Minimal with raw angle, AGC, magnitude, status,
// configuration, position/angle range management, and OTP burn commands.
//
// Embeds As5600Minimal to inherit Angle, AngleRaw, IsMagnetDetected,
// IsMagnetTooStrong, IsMagnetTooWeak, and the constructor.
type As5600Full struct {
	*As5600Minimal
}

// NewAs5600Full creates a new As5600Full and verifies magnet presence.
func NewAs5600Full(t transport.Transport) (*As5600Full, error) {
	m, err := NewAs5600Minimal(t)
	if err != nil {
		return nil, err
	}
	return &As5600Full{As5600Minimal: m}, nil
}

// RawAngle reads the unscaled raw 12-bit angle count (0–4095) from
// RAW_ANGLE (0x0C-0x0D), unaffected by ZPOS/MPOS programming.
func (d *As5600Full) RawAngle() (uint16, error) {
	return d.As5600Minimal.readReg12(as5600RegRawAngleH)
}

// RawAngleDegrees reads the unscaled raw angle in degrees.
func (d *As5600Full) RawAngleDegrees() (float64, error) {
	r, err := d.RawAngle()
	if err != nil {
		return 0, err
	}
	return float64(r) * 360.0 / 4096.0, nil
}

// AGC reads the automatic gain control value.
//
// In 5 V mode the range is 0–255; in 3.3 V mode it is 0–127. Mid-range
// indicates optimal airgap.
func (d *As5600Full) AGC() (uint8, error) {
	return d.As5600Minimal.readReg8(as5600RegAGC)
}

// Magnitude reads the 12-bit CORDIC magnitude value.
func (d *As5600Full) Magnitude() (uint16, error) {
	return d.As5600Minimal.readReg12(as5600RegMagnitudeH)
}

// StatusByte reads the raw STATUS register byte (MH bit 5, ML bit 4, MD bit 3).
func (d *As5600Full) StatusByte() (uint8, error) {
	return d.As5600Minimal.readReg8(as5600RegStatus)
}

// Configure writes the CONF register (14 bits split across CONF_H/CONF_L).
//
// Reads the current CONF_H/CONF_L first to preserve the reserved bits in
// CONF_H[7:6], then masks in the new field values.
//
// pm: power mode 0–3 (NOM, LPM1, LPM2, LPM3).
// hyst: hysteresis 0–3 (off, 1 LSB, 2 LSB, 3 LSB).
// outs: output stage 0–2 (analog 0–VDD, analog 10–90% VDD, PWM).
// pwmf: PWM frequency 0–3 (115, 230, 460, 920 Hz).
// sf: slow filter 0–3 (16x, 8x, 4x, 2x).
// fth: fast filter threshold 0–7.
// wd: watchdog enable.
func (d *As5600Full) Configure(pm, hyst, outs, pwmf, sf, fth uint8, wd bool) error {
	confH, err := d.As5600Minimal.readReg8(as5600RegConfH)
	if err != nil {
		return err
	}
	confL, err := d.As5600Minimal.readReg8(as5600RegConfL)
	if err != nil {
		return err
	}
	confH = (confH & 0xC0) | boolToU8(wd)<<5 | (fth&0x07)<<2 | (sf & 0x03)
	confL = (pwmf&0x03)<<6 | (outs&0x03)<<4 | (hyst&0x03)<<2 | (pm & 0x03)
	return d.As5600Minimal.transport.Write([]byte{as5600RegConfH, confH, confL})
}

// SetZeroPosition writes the zero (start) position to volatile RAM.
//
// pos: 0–4095. Lost on power cycle unless followed by BurnAngle().
func (d *As5600Full) SetZeroPosition(pos uint16) error {
	return d.As5600Minimal.writeReg12(as5600RegZPosH, as5600RegZPosL, pos)
}

// SetMaxPosition writes the maximum (stop) position to volatile RAM.
func (d *As5600Full) SetMaxPosition(pos uint16) error {
	return d.As5600Minimal.writeReg12(as5600RegMPosH, as5600RegMPosL, pos)
}

// SetMaxAngle writes the maximum angle span to volatile RAM.
//
// span: 0–4095; must correspond to >= 18 degrees.
func (d *As5600Full) SetMaxAngle(span uint16) error {
	return d.As5600Minimal.writeReg12(as5600RegMAngH, as5600RegMAngL, span)
}

// ZeroPosition reads the zero (start) position (0–4095).
func (d *As5600Full) ZeroPosition() (uint16, error) {
	return d.As5600Minimal.readReg12(as5600RegZPosH)
}

// MaxPosition reads the maximum (stop) position (0–4095).
func (d *As5600Full) MaxPosition() (uint16, error) {
	return d.As5600Minimal.readReg12(as5600RegMPosH)
}

// MaxAngle reads the maximum angle span (0–4095).
func (d *As5600Full) MaxAngle() (uint16, error) {
	return d.As5600Minimal.readReg12(as5600RegMAngH)
}

// BurnCount reads the OTP burn count for ZPOS/MPOS (0–3).
//
// Remaining permanent writes = 3 - BurnCount().
func (d *As5600Full) BurnCount() (uint8, error) {
	v, err := d.As5600Minimal.readReg8(as5600RegZMCO)
	if err != nil {
		return 0, err
	}
	return v & 0x03, nil
}

// BurnAngle permanently burns ZPOS and MPOS to OTP.
//
// Requires MD=1 (magnet present) and ZMCO < 3. After burning, the standard
// OTP verification sequence (0x01, 0x11, 0x10 to 0xFF) is executed to reload
// and verify.
func (d *As5600Full) BurnAngle() error {
	status, err := d.As5600Minimal.readReg8(as5600RegStatus)
	if err != nil {
		return err
	}
	if status&as5600StatusMD == 0 {
		return fmt.Errorf("as5600: burn_angle requires magnet detected (MD=1)")
	}
	bc, err := d.BurnCount()
	if err != nil {
		return err
	}
	if bc >= 3 {
		return fmt.Errorf("as5600: burn_angle failed, ZMCO=3 (no remaining writes)")
	}
	if err := d.As5600Minimal.writeReg8(as5600RegBurn, 0x80); err != nil {
		return err
	}
	if err := d.As5600Minimal.writeReg8(as5600RegBurn, 0x01); err != nil {
		return err
	}
	if err := d.As5600Minimal.writeReg8(as5600RegBurn, 0x11); err != nil {
		return err
	}
	return d.As5600Minimal.writeReg8(as5600RegBurn, 0x10)
}

// BurnSetting permanently burns MANG and CONF to OTP.
//
// Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.
func (d *As5600Full) BurnSetting() error {
	bc, err := d.BurnCount()
	if err != nil {
		return err
	}
	if bc != 0 {
		return fmt.Errorf("as5600: burn_setting requires ZMCO=0")
	}
	if err := d.As5600Minimal.writeReg8(as5600RegBurn, 0x40); err != nil {
		return err
	}
	if err := d.As5600Minimal.writeReg8(as5600RegBurn, 0x01); err != nil {
		return err
	}
	if err := d.As5600Minimal.writeReg8(as5600RegBurn, 0x11); err != nil {
		return err
	}
	return d.As5600Minimal.writeReg8(as5600RegBurn, 0x10)
}

func boolToU8(b bool) uint8 {
	if b {
		return 1
	}
	return 0
}
