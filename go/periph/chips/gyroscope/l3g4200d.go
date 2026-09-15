// Package gyroscope contains drivers for standalone gyroscope sensors.
package gyroscope

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// L3G4200D register addresses.
const (
	l3g4200dRegWHOAMI     uint8 = 0x0F
	l3g4200dRegCtrlReg1   uint8 = 0x20
	l3g4200dRegCtrlReg2   uint8 = 0x21
	l3g4200dRegCtrlReg3   uint8 = 0x22
	l3g4200dRegCtrlReg4   uint8 = 0x23
	l3g4200dRegCtrlReg5   uint8 = 0x24
	l3g4200dRegOutTemp    uint8 = 0x26
	l3g4200dRegStatus     uint8 = 0x27
	l3g4200dRegOutXL      uint8 = 0x28
	l3g4200dRegOutXH      uint8 = 0x29
	l3g4200dRegOutYL      uint8 = 0x2A
	l3g4200dRegOutYH      uint8 = 0x2B
	l3g4200dRegOutZL      uint8 = 0x2C
	l3g4200dRegOutZH      uint8 = 0x2D
	l3g4200dRegFifoCtrl   uint8 = 0x2E
	l3g4200dRegFifoSrc    uint8 = 0x2F
	l3g4200dRegInt1Cfg    uint8 = 0x30
	l3g4200dRegInt1Src    uint8 = 0x31
	l3g4200dRegInt1ThsXH  uint8 = 0x32
	l3g4200dRegInt1ThsXL  uint8 = 0x33
	l3g4200dRegInt1ThsYH  uint8 = 0x34
	l3g4200dRegInt1ThsYL  uint8 = 0x35
	l3g4200dRegInt1ThsZH  uint8 = 0x36
	l3g4200dRegInt1ThsZL  uint8 = 0x37
	l3g4200dRegInt1Dur    uint8 = 0x38
)

// L3G4200D expected chip ID.
const l3g4200dChipID uint8 = 0xD3

// L3G4200D init defaults.
const (
	l3g4200dCtrlReg1Default uint8 = 0x0F
	l3g4200dCtrlReg4Default uint8 = 0x80
)

// L3G4200D output data rate codes (DR[1:0] in CTRL_REG1).
const (
	// L3G4200DODR100Hz is 100 Hz output data rate.
	L3G4200DODR100Hz uint8 = 0
	// L3G4200DODR200Hz is 200 Hz output data rate.
	L3G4200DODR200Hz uint8 = 1
	// L3G4200DODR400Hz is 400 Hz output data rate.
	L3G4200DODR400Hz uint8 = 2
	// L3G4200DODR800Hz is 800 Hz output data rate.
	L3G4200DODR800Hz uint8 = 3
)

// L3G4200D full-scale ranges.
const (
	// L3G4200DFS250DPS is ±250 dps full scale.
	L3G4200DFS250DPS uint16 = 250
	// L3G4200DFS500DPS is ±500 dps full scale.
	L3G4200DFS500DPS uint16 = 500
	// L3G4200DFS2000DPS is ±2000 dps full scale.
	L3G4200DFS2000DPS uint16 = 2000
)

// L3G4200D FIFO modes (FM[2:0] in FIFO_CTRL_REG).
const (
	// L3G4200DFIFOBypass disables the FIFO.
	L3G4200DFIFOBypass uint8 = 0
	// L3G4200DFIFOFIFO is collect mode.
	L3G4200DFIFOFIFO uint8 = 1
	// L3G4200DFIFOStream overwrites oldest sample when full.
	L3G4200DFIFOStream uint8 = 2
	// L3G4200DFIFOStreamToFIFO is stream-then-halt.
	L3G4200DFIFOStreamToFIFO uint8 = 3
	// L3G4200DFIFOBypassToStream is bypass-then-stream.
	L3G4200DFIFOBypassToStream uint8 = 4
)

// sensitivity returns the dps/digit sensitivity for a given full-scale.
func sensitivity(fullScale uint16) float32 {
	switch fullScale {
	case L3G4200DFS250DPS:
		return 8.75e-3
	case L3G4200DFS500DPS:
		return 17.5e-3
	case L3G4200DFS2000DPS:
		return 70.0e-3
	default:
		return 8.75e-3
	}
}

// int16Le unpacks two bytes in little-endian order into a signed 16-bit integer.
func int16Le(b []byte) int16 {
	v := int16(uint16(b[0]) | uint16(b[1])<<8)
	return v
}

// L3G4200DMinimal is the L3G4200D three-axis MEMS gyroscope driver — minimal interface.
//
// Default configuration baked in: 100 Hz ODR, 12.5 Hz LPF2 cutoff, ±250 dps
// full scale, BDU=1, all axes enabled, FIFO disabled, HPF disabled.
type L3G4200DMinimal struct {
	connection connection.Connection
	spi        bool
	fullScale  uint16
}

// NewL3G4200DMinimal creates an L3G4200DMinimal and runs the chip init sequence.
//
// Pass spi=true for SPI — per the L3G4200D SPI protocol, writes clear bits
// 7 and 6 of the register address (reg & 0x3F); reads set both bits
// (reg | 0xC0) for READ=1 and MS=1 auto-increment.
func NewL3G4200DMinimal(t connection.Connection, spi bool) (*L3G4200DMinimal, error) {
	d := &L3G4200DMinimal{
		connection: t,
		spi:        spi,
		fullScale:  L3G4200DFS250DPS,
	}
	if err := d.writeReg(l3g4200dRegCtrlReg4, l3g4200dCtrlReg4Default); err != nil {
		_ = err
	}
	if err := d.writeReg(l3g4200dRegCtrlReg1, l3g4200dCtrlReg1Default); err != nil {
		_ = err
	}
	// Sleep briefly for the chip to settle; matches init in Python/C++ drivers.
	time.Sleep(time.Millisecond)
	return d, nil
}

// writeReg writes a single byte to a register.
func (d *L3G4200DMinimal) writeReg(reg, val uint8) error {
	addr := reg
	if d.spi {
		addr &= 0x3F
	}
	return d.connection.Write([]byte{addr, val})
}

// readRegBytes reads n bytes from a register into buf.
func (d *L3G4200DMinimal) readRegBytes(reg uint8, n int) ([]byte, error) {
	var addr uint8
	if d.spi {
		addr = reg | 0xC0
	} else if n > 1 {
		addr = reg | 0x80
	} else {
		addr = reg
	}
	return d.connection.WriteRead([]byte{addr & 0xFF}, n)
}

// AngularRate reads angular rate on all three axes as a single burst transaction.
//
// Returns (x_rad_s, y_rad_s, z_rad_s).
func (d *L3G4200DMinimal) AngularRate() (float32, float32, float32, error) {
	buf, err := d.readRegBytes(l3g4200dRegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	sens := sensitivity(d.fullScale)
	xDps := float32(int16Le(buf[0:2])) * sens
	yDps := float32(int16Le(buf[2:4])) * sens
	zDps := float32(int16Le(buf[4:6])) * sens
	const kRad = float32(math.Pi / 180.0)
	return xDps * kRad, yDps * kRad, zDps * kRad, nil
}

// L3G4200DFull is the L3G4200D three-axis MEMS gyroscope driver — full interface.
// Extends L3G4200DMinimal with configuration, FIFO, high-pass filter,
// interrupts, axis-enable, and power-mode control.
type L3G4200DFull struct {
	*L3G4200DMinimal
	odr uint8
	bw  uint8
}

// NewL3G4200DFull creates an L3G4200DFull and runs the chip init sequence.
func NewL3G4200DFull(t connection.Connection, spi bool) (*L3G4200DFull, error) {
	m, err := NewL3G4200DMinimal(t, spi)
	if err != nil {
		return nil, err
	}
	return &L3G4200DFull{L3G4200DMinimal: m}, nil
}

// Configure sets ODR, LPF2 bandwidth, and full scale in one call.
//
// Parameters:
//   - odr        — ODR code 0-3 (L3G4200DODR100Hz through L3G4200DODR800Hz).
//   - bandwidth  — LPF2 bandwidth code 0-3.
//   - fullScale  — Full-scale dps (L3G4200DFS250DPS, 500, or 2000).
func (d *L3G4200DFull) Configure(odr, bandwidth uint8, fullScale uint16) error {
	if fullScale != L3G4200DFS250DPS && fullScale != L3G4200DFS500DPS && fullScale != L3G4200DFS2000DPS {
		return nil
	}
	d.odr = odr & 0x3
	d.bw = bandwidth & 0x3
	d.fullScale = fullScale
	ctrl1 := l3g4200dCtrlReg1Default | ((d.odr & 0x3) << 6) | ((d.bw & 0x3) << 4)
	if err := d.writeReg(l3g4200dRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	var fsBits uint8
	switch fullScale {
	case L3G4200DFS250DPS:
		fsBits = 0
	case L3G4200DFS500DPS:
		fsBits = 1
	default:
		fsBits = 2
	}
	return d.writeReg(l3g4200dRegCtrlReg4, l3g4200dCtrlReg4Default|((fsBits&0x3)<<4))
}

// SetFullScale updates the full-scale range.
func (d *L3G4200DFull) SetFullScale(fullScale uint16) error {
	if fullScale != L3G4200DFS250DPS && fullScale != L3G4200DFS500DPS && fullScale != L3G4200DFS2000DPS {
		return nil
	}
	d.fullScale = fullScale
	var fsBits uint8
	switch fullScale {
	case L3G4200DFS250DPS:
		fsBits = 0
	case L3G4200DFS500DPS:
		fsBits = 1
	default:
		fsBits = 2
	}
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg4, 1)
	if err != nil {
		return err
	}
	ctrl4 := (buf[0] & 0xCF) | ((fsBits & 0x3) << 4)
	return d.writeReg(l3g4200dRegCtrlReg4, ctrl4)
}

// WHOAMI reads WHO_AM_I (0x0F). Returns 0xD3 for a genuine L3G4200D.
func (d *L3G4200DFull) WHOAMI() (uint8, error) {
	buf, err := d.readRegBytes(l3g4200dRegWHOAMI, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// Status reads STATUS_REG (0x27) raw byte.
func (d *L3G4200DFull) Status() (uint8, error) {
	buf, err := d.readRegBytes(l3g4200dRegStatus, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// DataReady returns true if STATUS_REG.ZYXDA (bit 3) is set.
func (d *L3G4200DFull) DataReady() (bool, error) {
	st, err := d.Status()
	if err != nil {
		return false, err
	}
	return (st & 0x08) != 0, nil
}

// Temperature reads the relative temperature count.
//
// OUT_TEMP is an 8-bit signed value with a -1 °C/digit scale; no absolute
// calibration — useful only for tracking drift.
func (d *L3G4200DFull) Temperature() (int8, error) {
	buf, err := d.readRegBytes(l3g4200dRegOutTemp, 1)
	if err != nil {
		return 0, err
	}
	return int8(buf[0]), nil
}

// PowerDown enters power-down mode (PD=0 in CTRL_REG1).
func (d *L3G4200DFull) PowerDown() error {
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg1, 1)
	if err != nil {
		return err
	}
	return d.writeReg(l3g4200dRegCtrlReg1, buf[0]&0xF7)
}

// WakeUp wakes from power-down (PD=1); previously enabled axes restored.
func (d *L3G4200DFull) WakeUp() error {
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg1, 1)
	if err != nil {
		return err
	}
	return d.writeReg(l3g4200dRegCtrlReg1, buf[0]|0x08)
}

// Sleep enters sleep mode (PD=1, all axes disabled).
func (d *L3G4200DFull) Sleep() error {
	return d.writeReg(l3g4200dRegCtrlReg1, 0x08)
}

// EnableAxes enables or disables individual axes (Xen/Yen/Zen in CTRL_REG1).
func (d *L3G4200DFull) EnableAxes(x, y, z bool) error {
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg1, 1)
	if err != nil {
		return err
	}
	val := buf[0] & 0xF8
	if z {
		val |= 0x04
	}
	if y {
		val |= 0x02
	}
	if x {
		val |= 0x01
	}
	return d.writeReg(l3g4200dRegCtrlReg1, val)
}

// EnableFIFO configures and enables the FIFO.
func (d *L3G4200DFull) EnableFIFO(mode, watermark uint8) error {
	if mode > 4 {
		return nil
	}
	if watermark > 31 {
		watermark = 31
	}
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	if err := d.writeReg(l3g4200dRegCtrlReg5, buf[0]|0x40); err != nil {
		return err
	}
	return d.writeReg(l3g4200dRegFifoCtrl, ((mode&0x7)<<5)|(watermark&0x1F))
}

// DisableFIFO disables the FIFO.
func (d *L3G4200DFull) DisableFIFO() error {
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	if err := d.writeReg(l3g4200dRegCtrlReg5, buf[0]&^0x40); err != nil {
		return err
	}
	return d.writeReg(l3g4200dRegFifoCtrl, 0x00)
}

// FIFOSamples reads FSS[4:0] from FIFO_SRC_REG (number of stored samples, 0-31).
func (d *L3G4200DFull) FIFOSamples() (uint8, error) {
	buf, err := d.readRegBytes(l3g4200dRegFifoSrc, 1)
	if err != nil {
		return 0, err
	}
	return buf[0] & 0x1F, nil
}

// ReadFIFO drains all stored FIFO samples and returns them as rad/s tuples.
func (d *L3G4200DFull) ReadFIFO() ([][3]float32, error) {
	n, err := d.FIFOSamples()
	if err != nil {
		return nil, err
	}
	if n == 0 {
		return [][3]float32{}, nil
	}
	sens := sensitivity(d.fullScale)
	const kRad = float32(math.Pi / 180.0)
	buf, err := d.readRegBytes(l3g4200dRegOutXL, int(n)*6)
	if err != nil {
		return nil, err
	}
	out := make([][3]float32, n)
	for i := uint8(0); i < n; i++ {
		o := int(i) * 6
		x := float32(int16Le(buf[o:o+2])) * sens * kRad
		y := float32(int16Le(buf[o+2:o+4])) * sens * kRad
		z := float32(int16Le(buf[o+4:o+6])) * sens * kRad
		out[i] = [3]float32{x, y, z}
	}
	return out, nil
}

// EnableHighpass enables the high-pass filter on the output path.
func (d *L3G4200DFull) EnableHighpass(mode, cutoff uint8) error {
	if err := d.writeReg(l3g4200dRegCtrlReg2, ((mode&0x3)<<4)|(cutoff&0x0F)); err != nil {
		return err
	}
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	return d.writeReg(l3g4200dRegCtrlReg5, buf[0]|0x10)
}

// DisableHighpass clears HPen in CTRL_REG5.
func (d *L3G4200DFull) DisableHighpass() error {
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	return d.writeReg(l3g4200dRegCtrlReg5, buf[0]&^0x10)
}

// SetInterrupt configures INT1_CFG axis/direction events.
func (d *L3G4200DFull) SetInterrupt(xHigh, xLow, yHigh, yLow, zHigh, zLow, andMode, latch bool) error {
	var cfg uint8
	if andMode {
		cfg |= 0x80
	}
	if latch {
		cfg |= 0x40
	}
	if zHigh {
		cfg |= 0x20
	}
	if zLow {
		cfg |= 0x10
	}
	if yHigh {
		cfg |= 0x08
	}
	if yLow {
		cfg |= 0x04
	}
	if xHigh {
		cfg |= 0x02
	}
	if xLow {
		cfg |= 0x01
	}
	if err := d.writeReg(l3g4200dRegInt1Cfg, cfg); err != nil {
		return err
	}
	if cfg&0x3F != 0 {
		buf, err := d.readRegBytes(l3g4200dRegCtrlReg3, 1)
		if err != nil {
			return err
		}
		return d.writeReg(l3g4200dRegCtrlReg3, buf[0]|0x80)
	}
	return nil
}

// SetThreshold sets the interrupt threshold for one axis.
//
// Parameters:
//   - axis          — 'x', 'y', or 'z'.
//   - thresholdDps  — Threshold in dps. The 15-bit raw value is
//                      int(threshold_dps / sensitivity).
func (d *L3G4200DFull) SetThreshold(axis byte, thresholdDps float32) error {
	raw := uint16(int32(thresholdDps/sensitivity(d.fullScale))) & 0x7FFF
	var hi, lo uint8
	switch axis {
	case 'x':
		hi, lo = l3g4200dRegInt1ThsXH, l3g4200dRegInt1ThsXL
	case 'y':
		hi, lo = l3g4200dRegInt1ThsYH, l3g4200dRegInt1ThsYL
	case 'z':
		hi, lo = l3g4200dRegInt1ThsZH, l3g4200dRegInt1ThsZL
	default:
		return nil
	}
	if err := d.writeReg(hi, uint8((raw>>8)&0x7F)); err != nil {
		return err
	}
	return d.writeReg(lo, uint8(raw&0xFF))
}

// SetDuration sets INT1_DURATION.
func (d *L3G4200DFull) SetDuration(samples uint8, wait bool) error {
	val := uint8(0)
	if wait {
		val = 0x80
	}
	val |= samples & 0x7F
	return d.writeReg(l3g4200dRegInt1Dur, val)
}

// ReadIntSource reads INT1_SRC; reading clears the interrupt-active bit.
func (d *L3G4200DFull) ReadIntSource() (uint8, error) {
	buf, err := d.readRegBytes(l3g4200dRegInt1Src, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// SetDataReadyPin routes the data-ready signal to the DRDY/INT2 pin.
func (d *L3G4200DFull) SetDataReadyPin(enable bool) error {
	buf, err := d.readRegBytes(l3g4200dRegCtrlReg3, 1)
	if err != nil {
		return err
	}
	var ctrl3 uint8
	if enable {
		ctrl3 = buf[0] | 0x08
	} else {
		ctrl3 = buf[0] &^ 0x08
	}
	return d.writeReg(l3g4200dRegCtrlReg3, ctrl3)
}
