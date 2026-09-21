// Package gyroscope contains drivers for standalone gyroscope sensors.
package gyroscope

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// L3GD20H register addresses.
const (
	l3gd20hRegWHOAMI      uint8 = 0x0F
	l3gd20hRegCtrlReg1    uint8 = 0x20
	l3gd20hRegCtrlReg2    uint8 = 0x21
	l3gd20hRegCtrlReg3    uint8 = 0x22
	l3gd20hRegCtrlReg4    uint8 = 0x23
	l3gd20hRegCtrlReg5    uint8 = 0x24
	l3gd20hRegOutTemp     uint8 = 0x26
	l3gd20hRegStatus      uint8 = 0x27
	l3gd20hRegOutXL       uint8 = 0x28
	l3gd20hRegOutXH       uint8 = 0x29
	l3gd20hRegOutYL       uint8 = 0x2A
	l3gd20hRegOutYH       uint8 = 0x2B
	l3gd20hRegOutZL       uint8 = 0x2C
	l3gd20hRegOutZH       uint8 = 0x2D
	l3gd20hRegFifoCtrl    uint8 = 0x2E
	l3gd20hRegFifoSrc     uint8 = 0x2F
	l3gd20hRegInt1Cfg     uint8 = 0x30
	l3gd20hRegInt1Src     uint8 = 0x31
	l3gd20hRegInt1TshXH   uint8 = 0x32
	l3gd20hRegInt1TshXL   uint8 = 0x33
	l3gd20hRegInt1TshYH   uint8 = 0x34
	l3gd20hRegInt1TshYL   uint8 = 0x35
	l3gd20hRegInt1TshZH   uint8 = 0x36
	l3gd20hRegInt1TshZL   uint8 = 0x37
	l3gd20hRegInt1Dur     uint8 = 0x38
)

// L3GD20H/L3GD20 expected chip IDs.
const (
	l3gd20hChipIDL3GD20  uint8 = 0xD4
	l3gd20hChipIDL3GD20H uint8 = 0xD7
)

// L3GD20H init defaults.
const (
	l3gd20hCtrlReg1Default uint8 = 0x0F
	l3gd20hCtrlReg4Default uint8 = 0x80
)

// L3GD20H output data rate codes (DR[1:0] in CTRL_REG1).
const (
	// L3GD20HODR95Hz is 95 Hz output data rate.
	L3GD20HODR95Hz uint8 = 0
	// L3GD20HODR190Hz is 190 Hz output data rate.
	L3GD20HODR190Hz uint8 = 1
	// L3GD20HODR380Hz is 380 Hz output data rate.
	L3GD20HODR380Hz uint8 = 2
	// L3GD20HODR760Hz is 760 Hz output data rate.
	L3GD20HODR760Hz uint8 = 3
)

// L3GD20H full-scale codes (0=±250, 1=±500, 2=±2000 dps).
const (
	// L3GD20HFS250DPS is ±250 dps full scale.
	L3GD20HFS250DPS uint8 = 0
	// L3GD20HFS500DPS is ±500 dps full scale.
	L3GD20HFS500DPS uint8 = 1
	// L3GD20HFS2000DPS is ±2000 dps full scale.
	L3GD20HFS2000DPS uint8 = 2
)

// L3GD20H FIFO modes (FM[2:0] in FIFO_CTRL_REG).
const (
	// L3GD20HFIFOBypass disables the FIFO.
	L3GD20HFIFOBypass uint8 = 0
	// L3GD20HFIFOFIFO is FIFO mode.
	L3GD20HFIFOFIFO uint8 = 1
	// L3GD20HFIFOStream is stream mode.
	L3GD20HFIFOStream uint8 = 2
	// L3GD20HFIFOBypassToStream is bypass-to-stream mode.
	L3GD20HFIFOBypassToStream uint8 = 3
	// L3GD20HFIFOStreamToFIFO is stream-to-FIFO mode.
	L3GD20HFIFOStreamToFIFO uint8 = 7
)

// L3GD20H HPF modes (HPM[1:0] in CTRL_REG2).
const (
	// L3GD20HHPMNormal is normal mode (reset by reading REFERENCE).
	L3GD20HHPMNormal uint8 = 0
	// L3GD20HHPMReference is reference signal mode.
	L3GD20HHPMReference uint8 = 1
	// L3GD20HHPMNormalAlt is normal mode (alternate).
	L3GD20HHPMNormalAlt uint8 = 2
	// L3GD20HHPMAutoreset is autoreset on interrupt.
	L3GD20HHPMAutoreset uint8 = 3
)

// L3GD20H power mode strings.
const (
	// L3GD20HPowerNormal is normal mode (PD=1, all axes on).
	L3GD20HPowerNormal string = "normal"
	// L3GD20HPowerSleep is sleep mode (PD=1, all axes off).
	L3GD20HPowerSleep string = "sleep"
	// L3GD20HPowerPowerDown is power-down mode (PD=0).
	L3GD20HPowerPowerDown string = "power_down"
)

// l3gd20hSensitivity returns the dps/digit sensitivity for a given full-scale code.
func l3gd20hSensitivity(fullScale uint8) float32 {
	fsMap := []uint16{250, 500, 2000}
	fs := fsMap[fullScale]
	switch fs {
	case 250:
		return 8.75e-3
	case 500:
		return 17.5e-3
	case 2000:
		return 70.0e-3
	default:
		return 8.75e-3
	}
}

// l3gd20hInt16Le unpacks two bytes in little-endian order into a signed 16-bit integer.
func l3gd20hInt16Le(b []byte) int16 {
	v := int16(uint16(b[0]) | uint16(b[1])<<8)
	return v
}

// L3GD20HMinimal is the L3GD20H three-axis MEMS gyroscope driver — minimal interface.
//
// Default configuration baked in: 95 Hz ODR, default bandwidth, ±250 dps
// full scale, BDU=1, all axes enabled, 250 ms startup delay.
type L3GD20HMinimal struct {
	connection connection.Connection
	spi        bool
	fullScale  uint8 // code 0=250, 1=500, 2=2000 dps
}

// NewL3GD20HMinimal creates an L3GD20HMinimal and runs the chip init sequence.
//
// Pass spi=true for SPI — per the L3GD20H SPI protocol, writes clear bits
// 7 and 6 of the register address (reg & 0x3F); reads set both bits
// (reg | 0xC0) for READ=1 and MS=1 auto-increment.
func NewL3GD20HMinimal(t connection.Connection, spi bool) (*L3GD20HMinimal, error) {
	d := &L3GD20HMinimal{
		connection: t,
		spi:        spi,
		fullScale:  L3GD20HFS250DPS,
	}
	if err := d.writeReg(l3gd20hRegCtrlReg4, l3gd20hCtrlReg4Default); err != nil {
		return nil, err
	}
	if err := d.writeReg(l3gd20hRegCtrlReg1, l3gd20hCtrlReg1Default); err != nil {
		return nil, err
	}
	// 250 ms startup delay for gyroscope stabilization.
	time.Sleep(250 * time.Millisecond)
	return d, nil
}

// writeReg writes a single byte to a register.
func (d *L3GD20HMinimal) writeReg(reg, val uint8) error {
	addr := reg
	if d.spi {
		addr &= 0x3F
	}
	return d.connection.Write([]byte{addr, val})
}

// readRegBytes reads n bytes from a register into buf.
func (d *L3GD20HMinimal) readRegBytes(reg uint8, n int) ([]byte, error) {
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
func (d *L3GD20HMinimal) AngularRate() (float32, float32, float32, error) {
	buf, err := d.readRegBytes(l3gd20hRegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	sens := l3gd20hSensitivity(d.fullScale)
	xDps := float32(l3gd20hInt16Le(buf[0:2])) * sens
	yDps := float32(l3gd20hInt16Le(buf[2:4])) * sens
	zDps := float32(l3gd20hInt16Le(buf[4:6])) * sens
	const kRad = float32(math.Pi / 180.0)
	return xDps * kRad, yDps * kRad, zDps * kRad, nil
}

// L3GD20HFull is the L3GD20H three-axis MEMS gyroscope driver — full interface.
// Extends L3GD20HMinimal with configuration, FIFO, high-pass filter,
// interrupts, axis-enable, and power-mode control.
type L3GD20HFull struct {
	*L3GD20HMinimal
	odr uint8
	bw  uint8
}

// NewL3GD20HFull creates an L3GD20HFull and runs the chip init sequence.
func NewL3GD20HFull(t connection.Connection, spi bool) (*L3GD20HFull, error) {
	m, err := NewL3GD20HMinimal(t, spi)
	if err != nil {
		return nil, err
	}
	return &L3GD20HFull{L3GD20HMinimal: m}, nil
}

// Configure sets ODR, bandwidth, and full scale in one call.
//
// Parameters:
//   - odr       — ODR code 0-3 (L3GD20HODR95Hz through L3GD20HODR760Hz).
//   - bw        — Bandwidth code 0-3 (ODR-dependent; see datasheet Table 21).
//   - fullScale — Full-scale code (L3GD20HFS250DPS, L3GD20HFS500DPS, or L3GD20HFS2000DPS).
func (d *L3GD20HFull) Configure(odr, bw, fullScale uint8) error {
	if odr > 3 || bw > 3 || fullScale > 2 {
		return nil
	}
	d.odr = odr
	d.bw = bw
	d.fullScale = fullScale
	ctrl1 := l3gd20hCtrlReg1Default | ((d.odr & 0x3) << 6) | ((d.bw & 0x3) << 4)
	if err := d.writeReg(l3gd20hRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	return d.writeReg(l3gd20hRegCtrlReg4, l3gd20hCtrlReg4Default|((fullScale&0x3)<<4))
}

// AngularRateRaw reads raw 16-bit signed angular rate values.
//
// Returns (x_raw, y_raw, z_raw).
func (d *L3GD20HFull) AngularRateRaw() (int16, int16, int16, error) {
	buf, err := d.readRegBytes(l3gd20hRegOutXL, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	return l3gd20hInt16Le(buf[0:2]), l3gd20hInt16Le(buf[2:4]), l3gd20hInt16Le(buf[4:6]), nil
}

// Temperature reads the relative temperature count.
//
// OUT_TEMP is an 8-bit signed value with 1 LSB/°C sensitivity. There is
// no absolute calibration — it represents change from the device's
// power-on temperature baseline. Do not convert to absolute Celsius.
func (d *L3GD20HFull) Temperature() (int8, error) {
	buf, err := d.readRegBytes(l3gd20hRegOutTemp, 1)
	if err != nil {
		return 0, err
	}
	return int8(buf[0]), nil
}

// DataReady returns true if STATUS_REG.ZYXDA (bit 3) is set.
func (d *L3GD20HFull) DataReady() (bool, error) {
	buf, err := d.readRegBytes(l3gd20hRegStatus, 1)
	if err != nil {
		return false, err
	}
	return (buf[0] & 0x08) != 0, nil
}

// ConfigureHighpass configures the high-pass filter (CTRL_REG2).
//
// Parameters:
//   - mode   — HPF mode 0-3 (L3GD20HHPMNormal, L3GD20HHPMReference,
//               L3GD20HHPMNormalAlt, L3GD20HHPMAutoreset).
//   - cutoff — HPF cutoff code 0-15 (HPCF[3:0] in CTRL_REG2; actual
//               cutoff depends on ODR — see datasheet Table 21).
func (d *L3GD20HFull) ConfigureHighpass(mode, cutoff uint8) error {
	if mode > 3 || cutoff > 15 {
		return nil
	}
	ctrl2 := ((mode & 0x3) << 4) | (cutoff & 0x0F)
	return d.writeReg(l3gd20hRegCtrlReg2, ctrl2)
}

// EnableHighpass enables or disables the high-pass filter on the output path.
func (d *L3GD20HFull) EnableHighpass(enable bool) error {
	buf, err := d.readRegBytes(l3gd20hRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	var ctrl5 uint8
	if enable {
		ctrl5 = buf[0] | 0x10
	} else {
		ctrl5 = buf[0] &^ 0x10
	}
	return d.writeReg(l3gd20hRegCtrlReg5, ctrl5)
}

// ConfigureFIFO configures the FIFO (FIFO_CTRL_REG).
//
// Parameters:
//   - mode      — FIFO mode (L3GD20HFIFOBypass, L3GD20HFIFOFIFO, L3GD20HFIFOStream,
//                  L3GD20HFIFOBypassToStream, L3GD20HFIFOStreamToFIFO).
//   - watermark — Watermark threshold 0-31 (WTM[4:0]).
func (d *L3GD20HFull) ConfigureFIFO(mode, watermark uint8) error {
	validModes := map[uint8]bool{0: true, 1: true, 2: true, 3: true, 7: true}
	if !validModes[mode] || watermark > 31 {
		return nil
	}
	buf, err := d.readRegBytes(l3gd20hRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	if err := d.writeReg(l3gd20hRegCtrlReg5, buf[0]|0x40); err != nil {
		return err
	}
	return d.writeReg(l3gd20hRegFifoCtrl, ((mode&0x7)<<5)|(watermark&0x1F))
}

// EnableFIFO enables or disables the FIFO (FIFO_EN bit in CTRL_REG5).
func (d *L3GD20HFull) EnableFIFO(enable bool) error {
	buf, err := d.readRegBytes(l3gd20hRegCtrlReg5, 1)
	if err != nil {
		return err
	}
	var ctrl5 uint8
	if enable {
		ctrl5 = buf[0] | 0x40
	} else {
		ctrl5 = buf[0] &^ 0x40
		if err := d.writeReg(l3gd20hRegCtrlReg5, ctrl5); err != nil {
			return err
		}
		return d.writeReg(l3gd20hRegFifoCtrl, 0x00)
	}
	return d.writeReg(l3gd20hRegCtrlReg5, ctrl5)
}

// FIFOLevel reads number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).
//
// Returns number of stored samples (0-31).
func (d *L3GD20HFull) FIFOLevel() (uint8, error) {
	buf, err := d.readRegBytes(l3gd20hRegFifoSrc, 1)
	if err != nil {
		return 0, err
	}
	return buf[0] & 0x1F, nil
}

// ReadFIFO reads all available FIFO samples and returns as rad/s tuples.
//
// Returns slice of [3]float32 (x, y, z rad/s).
func (d *L3GD20HFull) ReadFIFO() ([][3]float32, error) {
	n, err := d.FIFOLevel()
	if err != nil {
		return nil, err
	}
	if n == 0 {
		return [][3]float32{}, nil
	}
	sens := l3gd20hSensitivity(d.fullScale)
	const kRad = float32(math.Pi / 180.0)
	buf, err := d.readRegBytes(l3gd20hRegOutXL, int(n)*6)
	if err != nil {
		return nil, err
	}
	out := make([][3]float32, n)
	for i := uint8(0); i < n; i++ {
		o := int(i) * 6
		x := float32(l3gd20hInt16Le(buf[o:o+2])) * sens * kRad
		y := float32(l3gd20hInt16Le(buf[o+2:o+4])) * sens * kRad
		z := float32(l3gd20hInt16Le(buf[o+4:o+6])) * sens * kRad
		out[i] = [3]float32{x, y, z}
	}
	return out, nil
}

// SetPowerMode sets the power mode (CTRL_REG1 PD and axis enable bits).
//
// Parameters:
//   - mode — "normal" (PD=1, all axes on), "sleep" (PD=1, all axes off),
//             or "power_down" (PD=0).
func (d *L3GD20HFull) SetPowerMode(mode string) error {
	buf, err := d.readRegBytes(l3gd20hRegCtrlReg1, 1)
	if err != nil {
		return err
	}
	var ctrl1 uint8
	switch mode {
	case L3GD20HPowerNormal:
		ctrl1 = (buf[0] & 0xF0) | 0x0F
	case L3GD20HPowerSleep:
		ctrl1 = (buf[0] & 0xF8) | 0x08
	case L3GD20HPowerPowerDown:
		ctrl1 = buf[0] & 0xF7
	default:
		return nil
	}
	return d.writeReg(l3gd20hRegCtrlReg1, ctrl1)
}