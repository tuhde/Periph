package humidity

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HTU31D command codes (single byte; the chip has no register pointer).
const (
	htu31dCmdReset      = 0x1E
	htu31dCmdHeaterOn   = 0x04
	htu31dCmdHeaterOff  = 0x02
	htu31dCmdConvert    = 0x40
	htu31dCmdReadTRH    = 0x00
	htu31dCmdReadRH     = 0x10
	htu31dCmdReadDiag   = 0x08
	htu31dCmdReadSerial = 0x0A
)

// HTU31D diagnostic byte masks.
const (
	HTU31DDiagNVMErr    = 0x80
	HTU31DDiagRHOverrun = 0x40
	HTU31DDiagRHHighErr = 0x20
	HTU31DDiagRHLowErr  = 0x10
	HTU31DDiagTOverrun  = 0x08
	HTU31DDiagTHighErr  = 0x04
	HTU31DDiagTLowErr   = 0x02
	HTU31DDiagHeaterOn  = 0x01
)

// Typical conversion times in seconds for OSR 0..3.
var (
	htu31dTRH = [4]float64{0.00111, 0.00214, 0.00421, 0.00834}
	htu31dTT  = [4]float64{0.00157, 0.00306, 0.00603, 0.01198}
)

// ErrHTU31DCRC is returned when the CRC-8 of a result does not match.
var ErrHTU31DCRC = errors.New("htu31d: CRC mismatch")

// ErrHTU31DResolution is returned by SetResolution for an oversampling value outside 0-3.
var ErrHTU31DResolution = errors.New("htu31d: osr must be 0-3")

// HTU31DCRC8 computes the HTU31D CRC-8 (polynomial 0x31, init 0x00, no final XOR).
func HTU31DCRC8(data []byte) byte {
	crc := byte(0x00)
	for _, b := range data {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = crc<<1 ^ 0x31
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

func htu31dHumidity(code uint16) float32 {
	rh := 100.0 * float32(code) / 65535.0
	if rh < 0 {
		return 0
	}
	if rh > 100 {
		return 100
	}
	return rh
}

func htu31dTemperature(code uint16) float32 {
	return -40.0 + 165.0*float32(code)/65535.0
}

// HTU31DMinimal is the HTU31D humidity and temperature sensor driver
// (TE Connectivity) — minimal interface.
//
// Each Read starts one conversion at the highest resolution (command 0x5E,
// OSR 3 for humidity and temperature), waits 25 ms, reads the temperature and
// humidity words (command 0x00) and verifies both CRC-8 bytes. Humidity is
// clamped to 0-100 %RH. The chip is command driven, so a plain I²C connection
// is used. The constructor does no bus access; Init performs the soft reset.
type HTU31DMinimal struct {
	conn  connection.Connection
	osrRH uint8
	osrT  uint8
}

// NewHTU31DMinimal creates the driver. conn must be a configured I²C
// connection bound to 0x40 or 0x41.
func NewHTU31DMinimal(conn connection.Connection) *HTU31DMinimal {
	return &HTU31DMinimal{conn: conn, osrRH: 3, osrT: 3}
}

func (d *HTU31DMinimal) command(code byte) error {
	return d.conn.Write([]byte{code})
}

// Init sends the soft reset (0x1E) and waits 15 ms; heater off, highest resolution.
func (d *HTU31DMinimal) Init() error {
	if err := d.command(htu31dCmdReset); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return nil
}

func (d *HTU31DMinimal) start() error {
	return d.command(htu31dCmdConvert | d.osrRH<<3 | d.osrT<<1)
}

func (d *HTU31DMinimal) wait() time.Duration {
	if d.osrRH == 3 && d.osrT == 3 {
		return 25 * time.Millisecond
	}
	return time.Duration(1.25 * (htu31dTRH[d.osrRH] + htu31dTT[d.osrT]) * float64(time.Second))
}

// readWords writes a read command and returns the CRC-verified 16-bit words.
func (d *HTU31DMinimal) readWords(command byte, count int) ([]uint16, error) {
	if err := d.command(command); err != nil {
		return nil, err
	}
	data, err := d.conn.Read(3 * count)
	if err != nil {
		return nil, err
	}
	words := make([]uint16, count)
	for i := 0; i < count; i++ {
		c := data[3*i : 3*i+3]
		if HTU31DCRC8(c[0:2]) != c[2] {
			return nil, ErrHTU31DCRC
		}
		words[i] = uint16(c[0])<<8 | uint16(c[1])
	}
	return words, nil
}

func (d *HTU31DMinimal) measure(command byte, count int) ([]uint16, error) {
	if err := d.start(); err != nil {
		return nil, err
	}
	time.Sleep(d.wait())
	return d.readWords(command, count)
}

// Read measures temperature (°C) and relative humidity (%RH, clamped to 0-100).
func (d *HTU31DMinimal) Read() (temperature, humidity float32, err error) {
	w, err := d.measure(htu31dCmdReadTRH, 2)
	if err != nil {
		return 0, 0, err
	}
	return htu31dTemperature(w[0]), htu31dHumidity(w[1]), nil
}

// HTU31DFull is the HTU31D driver with the complete feature set: single
// values, raw codes, separate oversampling, non-blocking conversion, heater,
// diagnostic byte, serial number, soft reset and dew point.
type HTU31DFull struct {
	HTU31DMinimal
}

// NewHTU31DFull creates the full driver; same argument as NewHTU31DMinimal.
func NewHTU31DFull(conn connection.Connection) *HTU31DFull {
	return &HTU31DFull{HTU31DMinimal{conn: conn, osrRH: 3, osrT: 3}}
}

// ReadHumidity runs one humidity measurement (command 0x10) and returns %RH, clamped to 0-100.
func (d *HTU31DFull) ReadHumidity() (float32, error) {
	w, err := d.measure(htu31dCmdReadRH, 1)
	if err != nil {
		return 0, err
	}
	return htu31dHumidity(w[0]), nil
}

// ReadTemperature runs one temperature measurement (the read stops after the temperature word) and returns °C.
func (d *HTU31DFull) ReadTemperature() (float32, error) {
	w, err := d.measure(htu31dCmdReadTRH, 1)
	if err != nil {
		return 0, err
	}
	return htu31dTemperature(w[0]), nil
}

// ReadRaw measures and returns the raw 16-bit codes.
func (d *HTU31DFull) ReadRaw() (tRaw, rhRaw uint16, err error) {
	w, err := d.measure(htu31dCmdReadTRH, 2)
	if err != nil {
		return 0, 0, err
	}
	return w[0], w[1], nil
}

// SetResolution selects the oversampling (0-3 each) of the following measurements.
func (d *HTU31DFull) SetResolution(osrRH, osrT uint8) error {
	if osrRH > 3 || osrT > 3 {
		return ErrHTU31DResolution
	}
	d.osrRH, d.osrT = osrRH, osrT
	return nil
}

// GetResolution returns the oversampling setting.
func (d *HTU31DFull) GetResolution() (osrRH, osrT uint8) {
	return d.osrRH, d.osrT
}

// ConversionTime returns the typical conversion time in seconds at the current oversampling.
func (d *HTU31DFull) ConversionTime() float64 {
	return htu31dTRH[d.osrRH] + htu31dTT[d.osrT]
}

// StartConversion writes the conversion command only (non-blocking use).
func (d *HTU31DFull) StartConversion() error {
	return d.start()
}

// ReadResult reads the result of the last conversion (command 0x00).
func (d *HTU31DFull) ReadResult() (temperature, humidity float32, err error) {
	w, err := d.readWords(htu31dCmdReadTRH, 2)
	if err != nil {
		return 0, 0, err
	}
	return htu31dTemperature(w[0]), htu31dHumidity(w[1]), nil
}

// SetHeater switches the on-chip heater on (0x04) or off (0x02).
func (d *HTU31DFull) SetHeater(on bool) error {
	if on {
		return d.command(htu31dCmdHeaterOn)
	}
	return d.command(htu31dCmdHeaterOff)
}

// Diagnostic reads the diagnostic byte (command 0x08); test it against the HTU31DDiag* masks.
func (d *HTU31DFull) Diagnostic() (byte, error) {
	if err := d.command(htu31dCmdReadDiag); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// IsHeaterOn reports whether the diagnostic byte shows the heater on.
func (d *HTU31DFull) IsHeaterOn() (bool, error) {
	v, err := d.Diagnostic()
	return v&HTU31DDiagHeaterOn != 0, err
}

// SerialNumber reads the 24-bit serial number (command 0x0A, CRC verified).
func (d *HTU31DFull) SerialNumber() (uint32, error) {
	if err := d.command(htu31dCmdReadSerial); err != nil {
		return 0, err
	}
	b, err := d.conn.Read(4)
	if err != nil {
		return 0, err
	}
	if HTU31DCRC8(b[0:3]) != b[3] {
		return 0, ErrHTU31DCRC
	}
	return uint32(b[0])<<16 | uint32(b[1])<<8 | uint32(b[2]), nil
}

// SoftReset sends the reset command (0x1E) and waits 15 ms; heater off,
// oversampling back to OSR 3 / 3.
func (d *HTU31DFull) SoftReset() error {
	if err := d.command(htu31dCmdReset); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	d.osrRH, d.osrT = 3, 3
	return nil
}

// DewPoint runs one measurement and computes the dew point in °C (Magnus formula over water).
func (d *HTU31DFull) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	r := float64(rh)
	if r <= 0 {
		r = 0.01
	}
	g := math.Log(r/100.0) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * g / (17.62 - g)), nil
}
