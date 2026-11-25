package pressure

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MS5607 command bytes (the chip has no register pointer).
const (
	ms5607CmdReset     = 0x1E
	ms5607CmdConvertD1 = 0x40
	ms5607CmdConvertD2 = 0x50
	ms5607CmdADCRead   = 0x00
	ms5607CmdPROMRead  = 0xA0
)

// MS5607 conversion kinds for StartConversion.
const (
	// MS5607Pressure selects a D1 (pressure) conversion.
	MS5607Pressure = 0
	// MS5607Temperature selects a D2 (temperature) conversion.
	MS5607Temperature = 1
)

var ms5607OSR = [5]uint16{256, 512, 1024, 2048, 4096}

// ms5607ConvUS is the maximum conversion time per OSR in microseconds (datasheet).
var ms5607ConvUS = [5]int{600, 1170, 2280, 4540, 9040}

// ErrMS5607CRC is returned by Init when the PROM CRC-4 does not match.
var ErrMS5607CRC = errors.New("ms5607: PROM CRC mismatch")

// ErrMS5607NotReady is returned when the ADC returns 0 (read too early,
// repeated, or no conversion started).
var ErrMS5607NotReady = errors.New("ms5607: ADC returned 0")

// ErrMS5607InvalidOSR is returned for an oversampling ratio other than 256,
// 512, 1024, 2048 or 4096.
var ErrMS5607InvalidOSR = errors.New("ms5607: invalid oversampling ratio")

// MS5607CRC4 computes the CRC-4 of the PROM (application note AN520):
// polynomial x⁴ + x³ + 1 over the 16 bytes of the eight words, MSB first;
// the low byte of word 7 (including the CRC nibble) counts as zero.
func MS5607CRC4(words [8]uint16) uint8 {
	words[7] &= 0xFF00
	var rem uint16
	for cnt := 0; cnt < 16; cnt++ {
		if cnt&1 == 1 {
			rem ^= words[cnt>>1] & 0x00FF
		} else {
			rem ^= words[cnt>>1] >> 8
		}
		for b := 0; b < 8; b++ {
			if rem&0x8000 != 0 {
				rem = rem<<1 ^ 0x3000
			} else {
				rem <<= 1
			}
		}
	}
	return uint8(rem>>12) & 0x0F
}

func ms5607OSRIndex(osr uint16) int {
	for i, v := range ms5607OSR {
		if v == osr {
			return i
		}
	}
	return -1
}

// ms5607Compensate applies the datasheet integer algorithm (truncating
// division, like C) and returns °C and Pa.
func ms5607Compensate(prom *[8]uint16, d1, d2 uint32, secondOrder bool) (float32, float32) {
	c1, c2, c3 := int64(prom[1]), int64(prom[2]), int64(prom[3])
	c4, c5, c6 := int64(prom[4]), int64(prom[5]), int64(prom[6])
	dt := int64(d2) - c5*256
	temp := 2000 + dt*c6/(1<<23)
	off := c2*(1<<17) + c4*dt/(1<<6)
	sens := c1*(1<<16) + c3*dt/(1<<7)
	if secondOrder && temp < 2000 {
		d := temp - 2000
		t2 := dt * dt / (1 << 31)
		off2 := 61 * d * d / 16
		sens2 := 2 * d * d
		if temp < -1500 {
			e := temp + 1500
			off2 += 15 * e * e
			sens2 += 8 * e * e
		}
		temp -= t2
		off -= off2
		sens -= sens2
	}
	p := (int64(d1)*sens/(1<<21) - off) / (1 << 15)
	return float32(temp) / 100.0, float32(p)
}

// MS5607Minimal is the MS5607 barometric pressure sensor driver
// (TE Connectivity) — minimal interface.
//
// Init resets the chip, reads the eight PROM words and verifies the CRC-4.
// Each Read converts the temperature (D2) and then the pressure (D1) at
// oversampling ratio 4096 (waiting 10 ms each) and applies the first- and
// second-order compensation. Pressure is in pascals (1 Pa = 0.01 mbar, the
// chip's native unit). The chip is command driven (no register pointer), so
// a plain I²C connection is used. The constructor does no bus access.
type MS5607Minimal struct {
	conn     connection.Connection
	osrIndex int
	prom     [8]uint16
}

// NewMS5607Minimal creates the driver. conn must be a configured I²C
// connection bound to 0x77 (or 0x76 with CSB high).
func NewMS5607Minimal(conn connection.Connection) *MS5607Minimal {
	return &MS5607Minimal{conn: conn, osrIndex: 4}
}

func (d *MS5607Minimal) readPROMWords() ([8]uint16, error) {
	var words [8]uint16
	for n := 0; n < 8; n++ {
		if err := d.conn.Write([]byte{byte(ms5607CmdPROMRead + 2*n)}); err != nil {
			return words, err
		}
		b, err := d.conn.Read(2)
		if err != nil {
			return words, err
		}
		words[n] = uint16(b[0])<<8 | uint16(b[1])
	}
	return words, nil
}

func (d *MS5607Minimal) readADC() (uint32, error) {
	if err := d.conn.Write([]byte{ms5607CmdADCRead}); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(3)
	if err != nil {
		return 0, err
	}
	v := uint32(b[0])<<16 | uint32(b[1])<<8 | uint32(b[2])
	if v == 0 {
		return 0, ErrMS5607NotReady
	}
	return v, nil
}

func (d *MS5607Minimal) start(kind int, index int) error {
	base := byte(ms5607CmdConvertD1)
	if kind == MS5607Temperature {
		base = ms5607CmdConvertD2
	}
	return d.conn.Write([]byte{base | byte(index<<1)})
}

func (d *MS5607Minimal) convert(kind int) (uint32, error) {
	if err := d.start(kind, d.osrIndex); err != nil {
		return 0, err
	}
	time.Sleep(time.Duration(ms5607ConvUS[d.osrIndex]+1000) * time.Microsecond)
	return d.readADC()
}

// Init resets the chip (0x1E), waits 3 ms, reads the PROM and verifies its
// CRC-4 (ErrMS5607CRC on a mismatch).
func (d *MS5607Minimal) Init() error {
	if err := d.conn.Write([]byte{ms5607CmdReset}); err != nil {
		return err
	}
	time.Sleep(3 * time.Millisecond)
	words, err := d.readPROMWords()
	if err != nil {
		return err
	}
	if MS5607CRC4(words) != uint8(words[7]&0x0F) {
		return ErrMS5607CRC
	}
	d.prom = words
	return nil
}

// Read measures temperature and pressure (OSR 4096, second-order
// compensation) and returns °C and Pa.
func (d *MS5607Minimal) Read() (float32, float32, error) {
	d2, err := d.convert(MS5607Temperature)
	if err != nil {
		return 0, 0, err
	}
	d1, err := d.convert(MS5607Pressure)
	if err != nil {
		return 0, 0, err
	}
	t, p := ms5607Compensate(&d.prom, d1, d2, true)
	return t, p, nil
}

// MS5607Full is the MS5607 driver with the complete feature set: single
// values, raw codes, oversampling, non-blocking conversion, PROM access and
// altitude. It embeds MS5607Minimal.
type MS5607Full struct {
	MS5607Minimal
}

// NewMS5607Full creates the full driver; same argument as NewMS5607Minimal.
func NewMS5607Full(conn connection.Connection) *MS5607Full {
	return &MS5607Full{MS5607Minimal{conn: conn, osrIndex: 4}}
}

// ReadRaw converts D2 and D1 at the current OSR and returns the raw 24-bit
// codes (d1 pressure, d2 temperature).
func (d *MS5607Full) ReadRaw() (uint32, uint32, error) {
	d2, err := d.convert(MS5607Temperature)
	if err != nil {
		return 0, 0, err
	}
	d1, err := d.convert(MS5607Pressure)
	return d1, d2, err
}

// ReadPressure measures and returns the compensated pressure in Pa.
func (d *MS5607Full) ReadPressure() (float32, error) {
	_, p, err := d.Read()
	return p, err
}

// ReadTemperature converts D2 only and returns the temperature in °C (with
// the low-temperature correction).
func (d *MS5607Full) ReadTemperature() (float32, error) {
	d2, err := d.convert(MS5607Temperature)
	if err != nil {
		return 0, err
	}
	t, _ := ms5607Compensate(&d.prom, 0, d2, true)
	return t, nil
}

// SetOversampling sets the oversampling ratio (256, 512, 1024, 2048 or 4096)
// used for both conversions; ErrMS5607InvalidOSR for any other value.
func (d *MS5607Full) SetOversampling(osr uint16) error {
	i := ms5607OSRIndex(osr)
	if i < 0 {
		return ErrMS5607InvalidOSR
	}
	d.osrIndex = i
	return nil
}

// Oversampling returns the current oversampling ratio (256 … 4096).
func (d *MS5607Full) Oversampling() uint16 { return ms5607OSR[d.osrIndex] }

// ConversionTime returns the maximum conversion time for the oversampling
// ratio osr (0 = the current one); ErrMS5607InvalidOSR for an invalid ratio.
func (d *MS5607Full) ConversionTime(osr uint16) (time.Duration, error) {
	i := d.osrIndex
	if osr != 0 {
		i = ms5607OSRIndex(osr)
		if i < 0 {
			return 0, ErrMS5607InvalidOSR
		}
	}
	return time.Duration(ms5607ConvUS[i]) * time.Microsecond, nil
}

// StartConversion writes a conversion command without waiting (non-blocking
// use). kind is MS5607Pressure or MS5607Temperature; osr 0 means the current
// ratio. Wait at least ConversionTime before ReadADC.
func (d *MS5607Full) StartConversion(kind int, osr uint16) error {
	i := d.osrIndex
	if osr != 0 {
		i = ms5607OSRIndex(osr)
		if i < 0 {
			return ErrMS5607InvalidOSR
		}
	}
	return d.start(kind, i)
}

// ReadADC sends the ADC read command and returns the 24-bit result of the
// last conversion; ErrMS5607NotReady if the chip returns 0.
func (d *MS5607Full) ReadADC() (uint32, error) { return d.readADC() }

// Compensate is a pure computation with the stored coefficients (no bus
// access); it returns °C and Pa.
func (d *MS5607Full) Compensate(d1, d2 uint32, secondOrder bool) (float32, float32) {
	return ms5607Compensate(&d.prom, d1, d2, secondOrder)
}

// Reset resets the chip (0x1E), waits 3 ms and reloads and verifies the PROM.
func (d *MS5607Full) Reset() error { return d.Init() }

// ReadPROM reads the eight PROM words from the chip.
func (d *MS5607Full) ReadPROM() ([8]uint16, error) { return d.readPROMWords() }

// Coefficients returns the calibration coefficients C1 … C6 read at Init.
func (d *MS5607Full) Coefficients() [6]uint16 {
	return [6]uint16{d.prom[1], d.prom[2], d.prom[3], d.prom[4], d.prom[5], d.prom[6]}
}

// PROMCRCOK reports whether the CRC-4 of the PROM words read at Init matches
// (no bus access).
func (d *MS5607Full) PROMCRCOK() bool { return MS5607CRC4(d.prom) == uint8(d.prom[7]&0x0F) }

// SerialCode returns the serial code (PROM word 7, bits 15:4).
func (d *MS5607Full) SerialCode() uint16 { return (d.prom[7] >> 4) & 0x0FFF }

// Altitude returns the altitude in metres from one pressure reading
// (international barometric formula) relative to seaLevelPa.
func (d *MS5607Full) Altitude(seaLevelPa float32) (float32, error) {
	p, err := d.ReadPressure()
	if err != nil {
		return 0, err
	}
	return float32(44330.0 * (1.0 - math.Pow(float64(p/seaLevelPa), 1.0/5.255))), nil
}
