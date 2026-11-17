package environmental

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// aht10Mock is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. AHT10 only uses the plain streamed Read/Write pattern
// (command-based protocol, no register addressing), backed by a FIFO queue:
// preload responses with queueRead; each Read(n) call pops the next one.
type aht10Mock struct {
	writes    [][]byte
	readQueue [][]byte
}

func newAHT10Mock() *aht10Mock {
	return &aht10Mock{}
}

func (m *aht10Mock) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *aht10Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *aht10Mock) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *aht10Mock) WriteRead(data []byte, n int) ([]byte, error) {
	return m.Read(n)
}

func (m *aht10Mock) Close() error                { return nil }
func (m *aht10Mock) Enable()                     {}
func (m *aht10Mock) Disable()                    {}
func (m *aht10Mock) IsEnabled() bool             { return true }
func (m *aht10Mock) IntPin() connection.InputPin { return nil }
func (m *aht10Mock) EnPin() connection.OutputPin { return nil }

func (m *aht10Mock) writesContain(expected []byte) bool {
	for _, w := range m.writes {
		if bytes.Equal(w, expected) {
			return true
		}
	}
	return false
}

// aht10Measurement is a 6-byte response encoding humidity=50.0%, temperature=50.0 degC:
// rawRH = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
// rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
var aht10Measurement = []byte{0x08, 0x80, 0x00, 0x08, 0x00, 0x00}

func TestAHT10FullAPI(t *testing.T) {
	conn := newAHT10Mock()

	// CAL bit set after the init command: no soft-reset retry.
	conn.queueRead([]byte{0x08})

	sensor, err := NewAHT10Full(conn)
	if err != nil {
		t.Fatalf("NewAHT10Full: %v", err)
	}
	if !conn.writesContain([]byte{0xE1, 0x08, 0x00}) {
		t.Errorf("init: expected 0xE1 init command")
	}
	if conn.writesContain([]byte{0xBA}) {
		t.Errorf("init: unexpected soft-reset write")
	}

	conn.queueRead(aht10Measurement)
	temp, hum, err := sensor.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if !conn.writesContain([]byte{0xAC, 0x33, 0x00}) {
		t.Errorf("Read: expected trigger command write")
	}
	if temp != 50.0 || hum != 50.0 {
		t.Errorf("Read() = (%v, %v), want (50, 50)", temp, hum)
	}

	conn.queueRead(aht10Measurement)
	if v, _ := sensor.ReadTemperature(); v != 50.0 {
		t.Errorf("ReadTemperature() = %v, want 50", v)
	}
	conn.queueRead(aht10Measurement)
	if v, _ := sensor.ReadHumidity(); v != 50.0 {
		t.Errorf("ReadHumidity() = %v, want 50", v)
	}

	conn.queueRead(aht10Measurement)
	if rh, rt, _ := sensor.ReadRaw(); rh != 0x80000 || rt != 0x80000 {
		t.Errorf("ReadRaw() = (%#x, %#x), want (0x80000, 0x80000)", rh, rt)
	}

	// BUSY on the first read, cleared on the second.
	conn.queueRead([]byte{0x88, 0x80, 0x00, 0x08, 0x00, 0x00})
	conn.queueRead(aht10Measurement)
	if v, err := sensor.ReadTemperature(); err != nil || v != 50.0 {
		t.Errorf("ReadTemperature() after BUSY = (%v, %v), want 50", v, err)
	}

	// Spec example: raw_t = 0x6B300 -> 33.74 degC
	conn.queueRead([]byte{0x08, 0x80, 0x00, 0x06, 0xB3, 0x00})
	if v, _ := sensor.ReadTemperature(); v < 33.73 || v > 33.75 {
		t.Errorf("ReadTemperature() example = %v, want ~33.74", v)
	}

	conn.queueRead(aht10Measurement)
	if v, _ := sensor.DewPoint(); v < 36.6 || v > 36.8 {
		t.Errorf("DewPoint() = %v, want ~36.7", v)
	}

	conn.queueRead([]byte{0x08})
	if v, _ := sensor.ReadStatus(); v != 0x08 {
		t.Errorf("ReadStatus() = %#x, want 0x08", v)
	}

	if err := sensor.SoftReset(); err != nil {
		t.Fatalf("SoftReset: %v", err)
	}
	if !conn.writesContain([]byte{0xBA}) {
		t.Errorf("SoftReset: expected soft-reset write")
	}

	conn.queueRead([]byte{0x08})
	if ok, _ := sensor.IsCalibrated(); !ok {
		t.Errorf("IsCalibrated() = false, want true")
	}
	conn.queueRead([]byte{0x00})
	if ok, _ := sensor.IsCalibrated(); ok {
		t.Errorf("IsCalibrated() = true, want false")
	}
	conn.queueRead([]byte{0x80})
	if ok, _ := sensor.IsBusy(); !ok {
		t.Errorf("IsBusy() = false, want true")
	}
	conn.queueRead([]byte{0x00})
	if ok, _ := sensor.IsBusy(); ok {
		t.Errorf("IsBusy() = true, want false")
	}
}

func TestAHT10NotCalibrated(t *testing.T) {
	conn := newAHT10Mock()
	conn.queueRead([]byte{0x00})
	conn.queueRead([]byte{0x00})
	if _, err := NewAHT10Minimal(conn); err != ErrAHT10NotCalibrated {
		t.Errorf("NewAHT10Minimal err = %v, want ErrAHT10NotCalibrated", err)
	}
	if !conn.writesContain([]byte{0xBA}) {
		t.Errorf("expected soft-reset retry")
	}
}
