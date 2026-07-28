package environmental

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. AHT21 only uses the plain streamed Read/Write pattern
// (command-based protocol, no register addressing), backed by a FIFO queue:
// preload responses with queueRead; each Read(n) call pops the next one.
type mockConnection struct {
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	return m.Read(n)
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func (m *mockConnection) writesContain(expected []byte) bool {
	for _, w := range m.writes {
		if bytes.Equal(w, expected) {
			return true
		}
	}
	return false
}

// measurement is a 6-byte response encoding humidity=50.0%, temperature=50.0 degC:
// rawRH = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
// rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
var measurement = []byte{0x00, 0x80, 0x00, 0x08, 0x00, 0x00}

func TestAHT21FullAPI(t *testing.T) {
	conn := newMockConnection()

	// Status byte with both CAL (0x08) and the paired 0x10 bit set, so the
	// (status & 0x18) != 0x18 check in NewAHT21Minimal passes and no
	// soft-reset/recalibration path runs.
	conn.queueRead([]byte{0x18})

	sensor, err := NewAHT21Full(conn)
	if err != nil {
		t.Fatalf("NewAHT21Full: %v", err)
	}
	if conn.writesContain([]byte{0xBA}) {
		t.Errorf("init: unexpected soft-reset write")
	}

	conn.queueRead(measurement)
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

	conn.queueRead(measurement)
	if v, _ := sensor.ReadTemperature(); v != 50.0 {
		t.Errorf("ReadTemperature() = %v, want 50", v)
	}

	conn.queueRead(measurement)
	if v, _ := sensor.ReadHumidity(); v != 50.0 {
		t.Errorf("ReadHumidity() = %v, want 50", v)
	}

	// read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer.
	conn.queueRead(append(append([]byte{}, measurement...), 0x8B))
	t2, h2, crcOk, err := sensor.ReadWithCrc()
	if err != nil {
		t.Fatalf("ReadWithCrc: %v", err)
	}
	if t2 != 50.0 || h2 != 50.0 || !crcOk {
		t.Errorf("ReadWithCrc() = (%v, %v, %v), want (50, 50, true)", t2, h2, crcOk)
	}

	conn.queueRead(append(append([]byte{}, measurement...), 0x00))
	if _, _, crcOk, _ := sensor.ReadWithCrc(); crcOk {
		t.Errorf("ReadWithCrc(): expected crcOk=false for corrupted trailer")
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
