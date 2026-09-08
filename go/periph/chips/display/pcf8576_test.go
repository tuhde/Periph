package display

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. PCF8576 is write-only, so only Write matters here;
// every call is logged to writes for assertions.
type mockConnection struct {
	writes [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{}
}

func (m *mockConnection) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error)             { return make([]byte, n), nil }
func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func lastWrite(writes [][]byte) []byte {
	if len(writes) == 0 {
		return nil
	}
	return writes[len(writes)-1]
}

func zeros21() []byte {
	buf := make([]byte, 21)
	buf[0] = 0x00
	return buf
}

func TestPCF8576Init(t *testing.T) {
	conn := newMockConnection()
	sensor, err := NewPCF8576Full(conn)
	if err != nil {
		t.Fatalf("NewPCF8576Full: %v", err)
	}
	if !bytes.Equal(conn.writes[0], []byte{0x48}) {
		t.Errorf("init mode write = % X, want [48]", conn.writes[0])
	}
	if !bytes.Equal(conn.writes[1], zeros21()) {
		t.Errorf("init clear write = % X, want %d zero bytes", conn.writes[1], 21)
	}
	_ = sensor
}

func TestPCF8576Clear(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)
	if err := sensor.Clear(); err != nil {
		t.Fatalf("Clear: %v", err)
	}
	n := len(conn.writes)
	if !bytes.Equal(conn.writes[n-2], []byte{0x48}) || !bytes.Equal(conn.writes[n-1], zeros21()) {
		t.Errorf("Clear writes = %v, want [48] and 21 zero bytes", conn.writes[n-2:])
	}
}

func TestPCF8576WriteRaw(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	if err := sensor.WriteRaw(5, []byte{0xAB, 0xCD}); err != nil {
		t.Fatalf("WriteRaw: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x05, 0xAB, 0xCD}) {
		t.Errorf("WriteRaw write = % X, want [05 AB CD]", lastWrite(conn.writes))
	}

	nBefore := len(conn.writes)
	if err := sensor.WriteRaw(3, nil); err != nil {
		t.Fatalf("WriteRaw (empty): %v", err)
	}
	if len(conn.writes) != nBefore {
		t.Errorf("WriteRaw(empty) should be a no-op, got %d new writes", len(conn.writes)-nBefore)
	}
}

func TestPCF8576SetDigit7seg(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	// digit '7' -> 0xE0, at RAM address 3*2=6.
	if err := sensor.SetDigit7seg(3, PCF8576SevenSeg[7]); err != nil {
		t.Fatalf("SetDigit7seg: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x06, 0xE0}) {
		t.Errorf("SetDigit7seg write = % X, want [06 E0]", lastWrite(conn.writes))
	}
}

func TestPCF8576EnableDisable(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	if err := sensor.Disable(); err != nil {
		t.Fatalf("Disable: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x40}) {
		t.Errorf("Disable write = % X, want [40]", lastWrite(conn.writes))
	}

	if err := sensor.Enable(); err != nil {
		t.Fatalf("Enable: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x48}) {
		t.Errorf("Enable write = % X, want [48]", lastWrite(conn.writes))
	}
}

func TestPCF8576SetMode(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	cases := []struct {
		backplanes, bias, want uint8
	}{
		{PCF8576Backplanes1, PCF8576Bias1_2, 0x4D}, // 0x40|8|4|1
		{PCF8576Backplanes2, PCF8576Bias1_3, 0x4A}, // 0x40|8|0|2
		{PCF8576Backplanes3, PCF8576Bias1_3, 0x4B}, // 0x40|8|0|3
		{PCF8576Backplanes4, PCF8576Bias1_3, 0x48}, // 0x40|8|0|0
	}
	for _, c := range cases {
		if err := sensor.SetMode(c.backplanes, c.bias); err != nil {
			t.Fatalf("SetMode(%d, %d): %v", c.backplanes, c.bias, err)
		}
		if got := lastWrite(conn.writes); len(got) != 1 || got[0] != c.want {
			t.Errorf("SetMode(%d, %d) write = % X, want [%02X]", c.backplanes, c.bias, got, c.want)
		}
	}
}

func TestPCF8576SetBlink(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	if err := sensor.SetBlink(PCF8576Blink1Hz, false); err != nil {
		t.Fatalf("SetBlink: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x72}) { // 0x70|0|2
		t.Errorf("SetBlink write = % X, want [72]", lastWrite(conn.writes))
	}

	if err := sensor.SetBlink(PCF8576Blink2Hz, true); err != nil {
		t.Fatalf("SetBlink (alt bank): %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x75}) { // 0x70|4|1
		t.Errorf("SetBlink (alt bank) write = % X, want [75]", lastWrite(conn.writes))
	}
}

func TestPCF8576SetBank(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	if err := sensor.SetBank(1, 0); err != nil {
		t.Fatalf("SetBank: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x7A}) { // 0x78|(1<<1)|0
		t.Errorf("SetBank write = % X, want [7A]", lastWrite(conn.writes))
	}
}

func TestPCF8576DeviceSelect(t *testing.T) {
	conn := newMockConnection()
	sensor, _ := NewPCF8576Full(conn)

	if err := sensor.DeviceSelect(5); err != nil {
		t.Fatalf("DeviceSelect: %v", err)
	}
	if !bytes.Equal(lastWrite(conn.writes), []byte{0x65}) { // 0x60|5
		t.Errorf("DeviceSelect write = % X, want [65]", lastWrite(conn.writes))
	}
}

func TestPCF8576MinimalDefaultBackplanes(t *testing.T) {
	// Regression test for a bug where NewPCF8576Minimal() initialised
	// `backplanes` to PCF8576Mode1_4 (0x00, a drive-mode bit pattern)
	// instead of the backplane count 4 - harmless only by coincidence,
	// since modeCode()'s default arm also happens to return PCF8576Mode1_4.
	conn := newMockConnection()
	sensor, err := NewPCF8576Minimal(conn)
	if err != nil {
		t.Fatalf("NewPCF8576Minimal: %v", err)
	}
	if sensor.backplanes != PCF8576Backplanes4 {
		t.Errorf("backplanes = %d, want %d (PCF8576Backplanes4)", sensor.backplanes, PCF8576Backplanes4)
	}
}
