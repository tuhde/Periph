// Package light contains drivers for Light, color, and proximity
// sensors (APDS-9960, etc.) over I²C.
package light

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// APDS-9960 register addresses.
const (
	apdsRegENABLE     uint8 = 0x80
	apdsRegATIME      uint8 = 0x81
	apdsRegWTIME      uint8 = 0x83
	apdsRegAILTL      uint8 = 0x84
	apdsRegAILTH      uint8 = 0x85
	apdsRegAIHTL      uint8 = 0x86
	apdsRegAIHTH      uint8 = 0x87
	apdsRegPILT       uint8 = 0x89
	apdsRegPIHT       uint8 = 0x8B
	apdsRegPERS       uint8 = 0x8C
	apdsRegCONFIG1    uint8 = 0x8D
	apdsRegPPULSE     uint8 = 0x8E
	apdsRegCONTROL    uint8 = 0x8F
	apdsRegCONFIG2    uint8 = 0x90
	apdsRegID         uint8 = 0x92
	apdsRegSTATUS     uint8 = 0x93
	apdsRegCDATAL     uint8 = 0x94
	apdsRegPDATA      uint8 = 0x9C
	apdsRegPOFFSET_UR uint8 = 0x9D
	apdsRegPOFFSET_DL uint8 = 0x9E
	apdsRegCONFIG3    uint8 = 0x9F
	apdsRegGPENTH     uint8 = 0xA0
	apdsRegGEXTH      uint8 = 0xA1
	apdsRegGCONF1     uint8 = 0xA2
	apdsRegGCONF2     uint8 = 0xA3
	apdsRegGPULSE     uint8 = 0xA6
	apdsRegGCONF4     uint8 = 0xAB
	apdsRegGFLVL      uint8 = 0xAE
	apdsRegGSTATUS    uint8 = 0xAF
	apdsRegPICLEAR    uint8 = 0xE5
	apdsRegCICLEAR    uint8 = 0xE6
	apdsRegAICLEAR    uint8 = 0xE7
	apdsRegGFIFO_U    uint8 = 0xFC

	apdsATIMEDefault   uint8 = 0xB6
	apdsCONTROLDefault uint8 = 0x01
	apdsCONFIG2Default uint8 = 0x01

	apdsIDExpected uint8 = 0xAB

	apdsChipIDBit uint8 = 0xAB
)

// APDS9960Minimal is the APDS-9960 digital proximity, ambient light,
// RGB color, and gesture sensor driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz Fast mode. The ALS/Color
// engine is enabled at construction with sensible defaults; the
// proximity and gesture engines are not used by Minimal.
//
// Default I²C address: 0x39 (fixed).
//
// Configuration defaults:
//   - ATIME = 0xB6 (72 cycles, ~200 ms integration, max count 65535)
//   - AGAIN = 1 (4× ALS gain; CONTROL bits 1:0)
//   - CONFIG2 = 0x01 (LED_BOOST=100%, reserved bit 0 set)
//   - PON + AEN enabled; no wait, no proximity, no gesture, no interrupts
type APDS9960Minimal struct {
	connection connection.Connection
	addr      uint8
}

// NewAPDS9960Minimal creates a new APDS9960Minimal and performs the
// mandatory initialisation sequence: 6 ms power-up wait, ID check,
// ENABLE=0, set ATIME/CONTROL/CONFIG2, ENABLE=0x03, 210 ms integration
// wait.
//
// connection must be a configured I²C connection bound to the device's
// 7-bit address (0x39, fixed).
func NewAPDS9960Minimal(t connection.Connection) (*APDS9960Minimal, error) {
	d := &APDS9960Minimal{connection: t, addr: 0x39}
	time.Sleep(6 * time.Millisecond)
	id, err := d.readReg(apdsRegID)
	if err != nil {
		return nil, err
	}
	if id != apdsIDExpected {
		return nil, &apdsIDError{got: id}
	}
	if err := d.writeReg(apdsRegENABLE, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(apdsRegATIME, apdsATIMEDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(apdsRegCONTROL, apdsCONTROLDefault); err != nil {
		return nil, err
	}
	if err := d.writeReg(apdsRegCONFIG2, apdsCONFIG2Default); err != nil {
		return nil, err
	}
	if err := d.writeReg(apdsRegENABLE, 0x03); err != nil {
		return nil, err
	}
	time.Sleep(210 * time.Millisecond)
	return d, nil
}

// apdsIDError is returned by NewAPDS9960Minimal when the device ID
// register does not match the expected 0xAB.
type apdsIDError struct {
	got uint8
}

func (e *apdsIDError) Error() string {
	return "apds9960: chip not found (ID=0x" + hexByte(e.got) + ", expected 0xAB)"
}

// hexByte formats a byte as two uppercase hex digits.
func hexByte(b uint8) string {
	const hex = "0123456789ABCDEF"
	return string([]byte{hex[(b>>4)&0xF], hex[b&0xF]})
}

func (d *APDS9960Minimal) writeReg(reg, value uint8) error {
	return d.connection.Write([]byte{reg, value})
}

func (d *APDS9960Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *APDS9960Minimal) readReg16LE(reg uint8) (uint16, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | (uint16(buf[1]) << 8), nil
}

// ColorClear reads the clear (unfiltered) channel via a 2-byte LE read.
//
// Returns the raw clear count, 0–65535.
func (d *APDS9960Minimal) ColorClear() (uint16, error) {
	return d.readReg16LE(apdsRegCDATAL)
}

// ColorRed reads the red channel. Burst-reads all 8 bytes from CDATAL
// to trigger the atomic latch; returns the red count.
//
// Returns the raw red count, 0–65535.
func (d *APDS9960Minimal) ColorRed() (uint16, error) {
	_, r, _, _, err := d.rgbc()
	return r, err
}

// ColorGreen reads the green channel. Burst-reads all 8 bytes from
// CDATAL to trigger the atomic latch; returns the green count.
//
// Returns the raw green count, 0–65535.
func (d *APDS9960Minimal) ColorGreen() (uint16, error) {
	_, _, g, _, err := d.rgbc()
	return g, err
}

// ColorBlue reads the blue channel. Burst-reads all 8 bytes from
// CDATAL to trigger the atomic latch; returns the blue count.
//
// Returns the raw blue count, 0–65535.
func (d *APDS9960Minimal) ColorBlue() (uint16, error) {
	_, _, _, b, err := d.rgbc()
	return b, err
}

// Color reads all four RGBC channels in one burst. Reading CDATAL
// at 0x94 atomically latches all eight bytes 0x94–0x9B.
//
// Returns (clear, red, green, blue), each 0–65535.
func (d *APDS9960Minimal) Color() (clear, red, green, blue uint16, err error) {
	c, r, g, b, err := d.rgbc()
	return c, r, g, b, err
}

// rgbc performs the 8-byte burst read and decodes the four channels.
func (d *APDS9960Minimal) rgbc() (uint16, uint16, uint16, uint16, error) {
	buf, err := d.connection.WriteRead([]byte{apdsRegCDATAL}, 8)
	if err != nil {
		return 0, 0, 0, 0, err
	}
	c := uint16(buf[0]) | (uint16(buf[1]) << 8)
	r := uint16(buf[2]) | (uint16(buf[3]) << 8)
	g := uint16(buf[4]) | (uint16(buf[5]) << 8)
	b := uint16(buf[6]) | (uint16(buf[7]) << 8)
	return c, r, g, b, nil
}

// APDS9960Full is the APDS-9960 driver — full interface. Extends
// APDS9960Minimal with proximity, gesture, wait engine, threshold and
// interrupt configuration, status queries, and device identification.
type APDS9960Full struct {
	*APDS9960Minimal
}

// NewAPDS9960Full creates a new APDS9960Full with the same
// initialisation as NewAPDS9960Minimal.
func NewAPDS9960Full(t connection.Connection) (*APDS9960Full, error) {
	m, err := NewAPDS9960Minimal(t)
	if err != nil {
		return nil, err
	}
	return &APDS9960Full{APDS9960Minimal: m}, nil
}

// ChipID returns the device ID register (expect 0xAB).
func (d *APDS9960Full) ChipID() (uint8, error) {
	return d.readReg(apdsRegID)
}

// EnableProximity sets or clears the PEN bit in ENABLE.
func (d *APDS9960Full) EnableProximity(enabled bool) error {
	return d.setEnableBit(0x04, enabled)
}

// Proximity reads the proximity count (0–255; higher means closer).
// PEN must be enabled.
func (d *APDS9960Full) Proximity() (uint8, error) {
	return d.readReg(apdsRegPDATA)
}

// EnableWait sets or clears the WEN bit in ENABLE.
func (d *APDS9960Full) EnableWait(enabled bool) error {
	return d.setEnableBit(0x08, enabled)
}

// ConfigureWait writes WTIME and updates CONFIG1.WLONG (preserving
// the reserved bits 6:5 = 1,1). long=true enables the 12× multiplier.
func (d *APDS9960Full) ConfigureWait(wtime uint8, long bool) error {
	if err := d.writeReg(apdsRegWTIME, wtime); err != nil {
		return err
	}
	c1, err := d.readReg(apdsRegCONFIG1)
	if err != nil {
		return err
	}
	if long {
		c1 |= 0x02
	} else {
		c1 &^= 0x02
	}
	c1 = (c1 & 0x03) | 0x60
	return d.writeReg(apdsRegCONFIG1, c1)
}

// ConfigureALS writes ATIME and updates the AGAIN field of CONTROL
// (preserving LDRIVE and PGAIN). again 0=1×, 1=4×, 2=16×, 3=64×.
func (d *APDS9960Full) ConfigureALS(atime, again uint8) error {
	if err := d.writeReg(apdsRegATIME, atime); err != nil {
		return err
	}
	ctrl, err := d.readReg(apdsRegCONTROL)
	if err != nil {
		return err
	}
	ctrl = (ctrl & 0xFC) | (again & 0x03)
	return d.writeReg(apdsRegCONTROL, ctrl)
}

// ConfigureProximityLED writes the LED drive, proximity gain, and
// pulse length/count. ldrive 0–3, pgain 0–3, ppulse 0–63 (N-1),
// pplen 0–3.
func (d *APDS9960Full) ConfigureProximityLED(ldrive, pgain, ppulse, pplen uint8) error {
	ctrl, err := d.readReg(apdsRegCONTROL)
	if err != nil {
		return err
	}
	ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03)
	if err := d.writeReg(apdsRegCONTROL, ctrl); err != nil {
		return err
	}
	return d.writeReg(apdsRegPPULSE, ((pplen&0x03)<<6)|(ppulse&0x3F))
}

// SetLEDBoost sets the LED_BOOST field of CONFIG2 (preserving bit 0
// which must stay 1). boost 0=100%, 1=150%, 2=200%, 3=300%.
func (d *APDS9960Full) SetLEDBoost(boost uint8) error {
	c2, err := d.readReg(apdsRegCONFIG2)
	if err != nil {
		return err
	}
	c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01
	return d.writeReg(apdsRegCONFIG2, c2)
}

// AlsThreshold sets ALS interrupt thresholds (16-bit LE).
func (d *APDS9960Full) AlsThreshold(low, high uint16) error {
	if err := d.writeReg(apdsRegAILTL, uint8(low)); err != nil {
		return err
	}
	if err := d.writeReg(apdsRegAILTH, uint8(low>>8)); err != nil {
		return err
	}
	if err := d.writeReg(apdsRegAIHTL, uint8(high)); err != nil {
		return err
	}
	return d.writeReg(apdsRegAIHTH, uint8(high>>8))
}

// ProximityThreshold sets proximity interrupt thresholds (8-bit).
func (d *APDS9960Full) ProximityThreshold(low, high uint8) error {
	if err := d.writeReg(apdsRegPILT, low); err != nil {
		return err
	}
	return d.writeReg(apdsRegPIHT, high)
}

// SetPersistence sets the interrupt persistence filters. ppers 0–15
// (proximity), apers 0–15 (ALS).
func (d *APDS9960Full) SetPersistence(ppers, apers uint8) error {
	return d.writeReg(apdsRegPERS, ((ppers&0x0F)<<4)|(apers&0x0F))
}

// EnableAlsInterrupt sets or clears the AIEN bit in ENABLE.
func (d *APDS9960Full) EnableAlsInterrupt(enabled bool) error {
	return d.setEnableBit(0x10, enabled)
}

// EnableProximityInterrupt sets or clears the PIEN bit in ENABLE.
func (d *APDS9960Full) EnableProximityInterrupt(enabled bool) error {
	return d.setEnableBit(0x20, enabled)
}

// ClearProximityInterrupt performs an address-only write to PICLEAR
// (no data byte — this is the chip's command-code clear mechanism).
func (d *APDS9960Full) ClearProximityInterrupt() error {
	return d.connection.Write([]byte{apdsRegPICLEAR})
}

// ClearAlsInterrupt performs an address-only write to CICLEAR.
func (d *APDS9960Full) ClearAlsInterrupt() error {
	return d.connection.Write([]byte{apdsRegCICLEAR})
}

// ClearAllInterrupts performs an address-only write to AICLEAR.
func (d *APDS9960Full) ClearAllInterrupts() error {
	return d.connection.Write([]byte{apdsRegAICLEAR})
}

// SetProximityOffset sets the proximity offsets for UP/RIGHT and
// DOWN/LEFT photodiodes (sign-magnitude, −127 to +127).
func (d *APDS9960Full) SetProximityOffset(ur, dl int8) error {
	if err := d.writeReg(apdsRegPOFFSET_UR, apdsEncodeOffset(ur)); err != nil {
		return err
	}
	return d.writeReg(apdsRegPOFFSET_DL, apdsEncodeOffset(dl))
}

// SetProximityMask masks individual photodiodes in proximity detection.
func (d *APDS9960Full) SetProximityMask(u, dl, l, r bool) error {
	c3, err := d.readReg(apdsRegCONFIG3)
	if err != nil {
		return err
	}
	c3 &= 0xF0
	if u {
		c3 |= 0x08
	}
	if dl {
		c3 |= 0x04
	}
	if l {
		c3 |= 0x02
	}
	if r {
		c3 |= 0x01
	}
	return d.writeReg(apdsRegCONFIG3, c3)
}

// EnableGesture sets or clears the GEN bit in ENABLE; also sets or
// clears GMODE in GCONF4 to enter/exit gesture collection mode.
func (d *APDS9960Full) EnableGesture(enabled bool) error {
	val, err := d.readReg(apdsRegENABLE)
	if err != nil {
		return err
	}
	if enabled {
		val |= 0x40
		if err := d.writeReg(apdsRegENABLE, val); err != nil {
			return err
		}
		g4, err := d.readReg(apdsRegGCONF4)
		if err != nil {
			return err
		}
		g4 |= 0x01
		return d.writeReg(apdsRegGCONF4, g4)
	}
	val &^= 0x40
	if err := d.writeReg(apdsRegENABLE, val); err != nil {
		return err
	}
	g4, err := d.readReg(apdsRegGCONF4)
	if err != nil {
		return err
	}
	g4 &^= 0x01
	return d.writeReg(apdsRegGCONF4, g4)
}

// ConfigureGesture sets the gesture engine parameters. ggain 0–3,
// gldrive 0–3, gpulse 0–63 (N-1), gplen 0–3, gwtime 0–7, gpenth 0–255,
// gexth 0–255.
func (d *APDS9960Full) ConfigureGesture(ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth uint8) error {
	if err := d.writeReg(apdsRegGPENTH, gpenth); err != nil {
		return err
	}
	if err := d.writeReg(apdsRegGEXTH, gexth); err != nil {
		return err
	}
	g2 := ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07)
	if err := d.writeReg(apdsRegGCONF2, g2); err != nil {
		return err
	}
	return d.writeReg(apdsRegGPULSE, ((gplen&0x03)<<6)|(gpulse&0x3F))
}

// GestureAvailable returns true if GSTATUS.GVALID is set.
func (d *APDS9960Full) GestureAvailable() (bool, error) {
	g, err := d.readReg(apdsRegGSTATUS)
	if err != nil {
		return false, err
	}
	return g&0x01 != 0, nil
}

// GestureFIFOLevel returns the number of 4-byte datasets currently in
// the gesture FIFO.
func (d *APDS9960Full) GestureFIFOLevel() (uint8, error) {
	return d.readReg(apdsRegGFLVL)
}

// ReadGestureFIFO reads up to maxSets datasets from the gesture FIFO
// and returns them as a slice of (U, D, L, R) tuples. Reads GFLVL first
// to determine how many datasets to pull.
func (d *APDS9960Full) ReadGestureFIFO(maxSets int) ([][4]uint8, error) {
	level, err := d.readReg(apdsRegGFLVL)
	if err != nil {
		return nil, err
	}
	count := int(level)
	if count > maxSets {
		count = maxSets
	}
	result := make([][4]uint8, count)
	for i := 0; i < count; i++ {
		raw, err := d.connection.WriteRead([]byte{apdsRegGFIFO_U}, 4)
		if err != nil {
			return nil, err
		}
		result[i] = [4]uint8{raw[0], raw[1], raw[2], raw[3]}
	}
	return result, nil
}

// ClearGestureFIFO sets GFIFO_CLR in GCONF4 (self-clearing).
func (d *APDS9960Full) ClearGestureFIFO() error {
	g4, err := d.readReg(apdsRegGCONF4)
	if err != nil {
		return err
	}
	g4 |= 0x04
	return d.writeReg(apdsRegGCONF4, g4)
}

// EnableGestureInterrupt sets or clears the GIEN bit in GCONF4.
func (d *APDS9960Full) EnableGestureInterrupt(enabled bool) error {
	g4, err := d.readReg(apdsRegGCONF4)
	if err != nil {
		return err
	}
	if enabled {
		g4 |= 0x02
	} else {
		g4 &^= 0x02
	}
	return d.writeReg(apdsRegGCONF4, g4)
}

// Status returns the raw STATUS register byte.
func (d *APDS9960Full) Status() (uint8, error) {
	return d.readReg(apdsRegSTATUS)
}

// IsAlsValid returns STATUS.AVALID.
func (d *APDS9960Full) IsAlsValid() (bool, error) {
	s, err := d.readReg(apdsRegSTATUS)
	if err != nil {
		return false, err
	}
	return s&0x01 != 0, nil
}

// IsProximityValid returns STATUS.PVALID.
func (d *APDS9960Full) IsProximityValid() (bool, error) {
	s, err := d.readReg(apdsRegSTATUS)
	if err != nil {
		return false, err
	}
	return s&0x02 != 0, nil
}

// IsAlsSaturated returns STATUS.CPSAT.
func (d *APDS9960Full) IsAlsSaturated() (bool, error) {
	s, err := d.readReg(apdsRegSTATUS)
	if err != nil {
		return false, err
	}
	return s&0x80 != 0, nil
}

// IsProximitySaturated returns STATUS.PGSAT.
func (d *APDS9960Full) IsProximitySaturated() (bool, error) {
	s, err := d.readReg(apdsRegSTATUS)
	if err != nil {
		return false, err
	}
	return s&0x40 != 0, nil
}

// setEnableBit sets or clears a single bit in the ENABLE register.
func (d *APDS9960Full) setEnableBit(mask uint8, enabled bool) error {
	val, err := d.readReg(apdsRegENABLE)
	if err != nil {
		return err
	}
	if enabled {
		val |= mask
	} else {
		val &^= mask
	}
	return d.writeReg(apdsRegENABLE, val)
}

// apdsEncodeOffset converts a signed offset to the chip's sign-magnitude
// encoding: bit 7 = sign (1 = negative), bits 6:0 = magnitude.
func apdsEncodeOffset(v int8) uint8 {
	if v < 0 {
		return 0x80 | (uint8(-v) & 0x7F)
	}
	return uint8(v) & 0x7F
}
