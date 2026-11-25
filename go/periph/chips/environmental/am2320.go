package environmental

import (
	"errors"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	am2320FuncRead    = 0x03
	am2320FuncWrite   = 0x10
	am2320MinInterval = 2 * time.Second
)

// ErrAM2320CRC is returned when a response fails its CRC-16, function code or byte count twice in a row.
var ErrAM2320CRC = errors.New("am2320: CRC mismatch")

// ErrAM2320Argument is returned for a register range or count outside the permitted limits.
var ErrAM2320Argument = errors.New("am2320: argument out of range")

// AM2320Error is the error for a sensor error response (function code with bit 7 set).
type AM2320Error struct {
	// Code is 0x80 (function not supported), 0x81 (illegal read address), 0x82 (write data
	// out of range), 0x83 (CRC error) or 0x84 (write disabled).
	Code byte
}

// Error implements the error interface.
func (e *AM2320Error) Error() string { return fmt.Sprintf("am2320: error response 0x%02X", e.Code) }

// AM2320CRC16 computes the Modbus CRC-16 used by the AM2320 (init 0xFFFF, polynomial 0xA001).
func AM2320CRC16(data []byte) uint16 {
	crc := uint16(0xFFFF)
	for _, b := range data {
		crc ^= uint16(b)
		for i := 0; i < 8; i++ {
			if crc&1 != 0 {
				crc = crc>>1 ^ 0xA001
			} else {
				crc >>= 1
			}
		}
	}
	return crc
}

func am2320Temperature(raw uint16) float32 {
	t := float32(raw&0x7FFF) / 10.0
	if raw&0x8000 != 0 {
		return -t
	}
	return t
}

// AM2320Minimal is the AM2320 humidity and temperature sensor driver (Aosong) — minimal interface.
//
// The sensor speaks a Modbus-style protocol: every read is a wake-up (a write the sensor does
// not acknowledge), a command write, a 2 ms wait and a read whose response carries a CRC-16.
// The sensor measures after each communication and returns that result on the next one, so
// Read returns the previous measurement; transactions are kept at least 2 s apart by sleeping
// the remainder. The constructor does no bus access; the first Read waits until 2 s after
// construction (power-up settling), and one retry follows a CRC mismatch or bus error.
type AM2320Minimal struct {
	conn        connection.Connection
	minInterval time.Duration
	last        time.Time
}

// NewAM2320Minimal creates the driver. conn must be a configured I²C connection bound to 0x5C
// (standard mode, at most 100 kHz).
func NewAM2320Minimal(conn connection.Connection) *AM2320Minimal {
	return &AM2320Minimal{conn: conn, minInterval: am2320MinInterval, last: time.Now()}
}

func (d *AM2320Minimal) waitInterval() {
	if d.minInterval <= 0 {
		return
	}
	if rest := d.minInterval - time.Since(d.last); rest > 0 {
		time.Sleep(rest)
	}
}

func (d *AM2320Minimal) wake() {
	_ = d.conn.Write([]byte{0x00}) // the sleeping sensor NACKs this; ignored
	time.Sleep(time.Millisecond)
}

func (d *AM2320Minimal) exchange(frame []byte, respLen int) ([]byte, error) {
	d.waitInterval()
	d.wake()
	if err := d.conn.Write(frame); err != nil {
		d.last = time.Now()
		return nil, err
	}
	time.Sleep(2 * time.Millisecond)
	resp, err := d.conn.Read(respLen)
	d.last = time.Now()
	return resp, err
}

func (d *AM2320Minimal) check(resp []byte, function byte) error {
	if len(resp) >= 1 && resp[0]&0x80 != 0 {
		return &AM2320Error{Code: resp[0]}
	}
	n := len(resp)
	if n < 4 || resp[0] != function {
		return ErrAM2320CRC
	}
	if AM2320CRC16(resp[:n-2]) != uint16(resp[n-2])|uint16(resp[n-1])<<8 {
		return ErrAM2320CRC
	}
	return nil
}

func (d *AM2320Minimal) transact(frame []byte, respLen int, function byte, verify func(resp []byte) bool) ([]byte, error) {
	var last error
	for attempt := 0; attempt < 2; attempt++ {
		resp, err := d.exchange(frame, respLen)
		if err == nil {
			err = d.check(resp, function)
			if err == nil && !verify(resp) {
				err = ErrAM2320CRC
			}
		}
		if err == nil {
			return resp, nil
		}
		var se *AM2320Error
		if errors.As(err, &se) {
			return nil, err
		}
		last = err
	}
	return nil, last
}

func (d *AM2320Minimal) readRegisters(start, count byte) ([]byte, error) {
	resp, err := d.transact([]byte{am2320FuncRead, start, count}, int(count)+4, am2320FuncRead,
		func(r []byte) bool { return r[1] == count })
	if err != nil {
		return nil, err
	}
	return resp[2 : 2+int(count)], nil
}

// Read reads temperature and humidity (registers 0x00-0x03): °C and %RH of the previous
// measurement.
func (d *AM2320Minimal) Read() (temperatureC, humidityPct float32, err error) {
	r, err := d.readRegisters(0x00, 4)
	if err != nil {
		return 0, 0, err
	}
	return am2320Temperature(uint16(r[2])<<8 | uint16(r[3])), float32(uint16(r[0])<<8|uint16(r[1])) / 10.0, nil
}

// AM2320Full is the AM2320 full interface — single values, raw words, fresh read, registers,
// user registers. It embeds AM2320Minimal.
type AM2320Full struct {
	*AM2320Minimal
}

// NewAM2320Full creates the full driver; same argument as NewAM2320Minimal.
func NewAM2320Full(conn connection.Connection) *AM2320Full {
	return &AM2320Full{AM2320Minimal: NewAM2320Minimal(conn)}
}

// ReadTemperature reads the temperature only (registers 0x02-0x03) in °C.
func (d *AM2320Full) ReadTemperature() (float32, error) {
	r, err := d.readRegisters(0x02, 2)
	if err != nil {
		return 0, err
	}
	return am2320Temperature(uint16(r[0])<<8 | uint16(r[1])), nil
}

// ReadHumidity reads the humidity only (registers 0x00-0x01) in %RH.
func (d *AM2320Full) ReadHumidity() (float32, error) {
	r, err := d.readRegisters(0x00, 2)
	if err != nil {
		return 0, err
	}
	return float32(uint16(r[0])<<8|uint16(r[1])) / 10.0, nil
}

// ReadRaw reads the raw 16-bit words; the temperature word is sign + magnitude.
func (d *AM2320Full) ReadRaw() (rawHumidity, rawTemperature uint16, err error) {
	r, err := d.readRegisters(0x00, 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(r[0])<<8 | uint16(r[1]), uint16(r[2])<<8 | uint16(r[3]), nil
}

// ReadFresh reads twice (at least 2 s apart) and returns the second, real-time result.
func (d *AM2320Full) ReadFresh() (temperatureC, humidityPct float32, err error) {
	if _, _, err = d.Read(); err != nil {
		return 0, 0, err
	}
	return d.Read()
}

// ReadRegisters reads count (1-10) registers from start (0x00-0x1F), CRC verified.
func (d *AM2320Full) ReadRegisters(start, count byte) ([]byte, error) {
	if count < 1 || count > 10 || start > 0x1F || int(start)+int(count) > 0x20 {
		return nil, ErrAM2320Argument
	}
	return d.readRegisters(start, count)
}

// WriteRegisters writes the user registers 0x10-0x13 (function 0x10 with CRC); data is 1-4 bytes.
func (d *AM2320Full) WriteRegisters(start byte, data []byte) error {
	n := len(data)
	if n < 1 || n > 4 || start < 0x10 || int(start)+n > 0x14 {
		return ErrAM2320Argument
	}
	frame := append([]byte{am2320FuncWrite, start, byte(n)}, data...)
	crc := AM2320CRC16(frame)
	frame = append(frame, byte(crc), byte(crc>>8))
	_, err := d.transact(frame, 5, am2320FuncWrite, func(r []byte) bool { return r[1] == start && r[2] == byte(n) })
	return err
}

// ReadDeviceInfo reads the device information (registers 0x08-0x0E).
func (d *AM2320Full) ReadDeviceInfo() (model uint16, version byte, deviceID uint32, err error) {
	r, err := d.readRegisters(0x08, 7)
	if err != nil {
		return 0, 0, 0, err
	}
	return uint16(r[0])<<8 | uint16(r[1]), r[2],
		uint32(r[3])<<24 | uint32(r[4])<<16 | uint32(r[5])<<8 | uint32(r[6]), nil
}

// ReadUserRegisters reads the two 16-bit user registers (0x10-0x13).
func (d *AM2320Full) ReadUserRegisters() (user1, user2 uint16, err error) {
	r, err := d.readRegisters(0x10, 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(r[0])<<8 | uint16(r[1]), uint16(r[2])<<8 | uint16(r[3]), nil
}

// WriteUserRegisters writes the two 16-bit user registers (0x10-0x13).
func (d *AM2320Full) WriteUserRegisters(user1, user2 uint16) error {
	return d.WriteRegisters(0x10, []byte{byte(user1 >> 8), byte(user1), byte(user2 >> 8), byte(user2)})
}

// Wake wakes the sensor: a dummy write (the NACK is ignored) and a 1 ms wait.
func (d *AM2320Full) Wake() { d.wake() }
