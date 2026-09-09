package led

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. WS2812B is write-only, so only Write is exercised;
// Read/WriteRead are stubbed to satisfy the interface.
type mockConnection struct {
	writes [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{}
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
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

func TestWS2812BFillGRBOrderAndClamp(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BMinimal(conn, 2)
	if err != nil {
		t.Fatalf("NewWS2812BMinimal: %v", err)
	}
	if err := d.Fill(10, 20, 30); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{20, 10, 30, 20, 10, 30}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Fill write = % X, want % X", lastWrite(conn.writes), want)
	}

	if err := d.Off(); err != nil {
		t.Fatalf("Off: %v", err)
	}
	want = []byte{0, 0, 0, 0, 0, 0}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Off write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestWS2812BSetPixelIndexClamp(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BFull(conn, 3)
	if err != nil {
		t.Fatalf("NewWS2812BFull: %v", err)
	}
	d.SetPixel(-1, 1, 2, 3)
	d.SetPixel(1, 4, 5, 6)
	d.SetPixel(100, 7, 8, 9)
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	want := []byte{2, 1, 3, 5, 4, 6, 8, 7, 9}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Show write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestWS2812BSetPixelZeroLength(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BFull(conn, 0)
	if err != nil {
		t.Fatalf("NewWS2812BFull: %v", err)
	}
	d.SetPixel(0, 1, 2, 3) // must not panic on empty buffer
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
}

func TestWS2812BBrightnessScaling(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BFull(conn, 1)
	if err != nil {
		t.Fatalf("NewWS2812BFull: %v", err)
	}
	d.SetPixel(0, 200, 100, 50)
	d.SetBrightness(128)
	if got := d.GetBrightness(); got != 128 {
		t.Errorf("GetBrightness() = %v, want 128", got)
	}
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	// stored order is GRB: g=100, r=200, b=50; sent = stored*128/255
	want := []byte{
		uint8(uint16(100) * 128 / 255),
		uint8(uint16(200) * 128 / 255),
		uint8(uint16(50) * 128 / 255),
	}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Show (brightness) write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestWS2812BRotateShiftsLeftByWholePixels(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BFull(conn, 4)
	if err != nil {
		t.Fatalf("NewWS2812BFull: %v", err)
	}
	d.SetPixel(0, 1, 0, 0)
	d.SetPixel(1, 2, 0, 0)
	d.SetPixel(2, 3, 0, 0)
	d.SetPixel(3, 4, 0, 0)
	d.Rotate(1)
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	// pixels [1,2,3,4] rotated left by one -> [2,3,4,1], stored as (g,r,b)=(0,n,0)
	want := []byte{0, 2, 0, 0, 3, 0, 0, 4, 0, 0, 1, 0}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Rotate+Show write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestWS2812BFillHSVRed(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BFull(conn, 1)
	if err != nil {
		t.Fatalf("NewWS2812BFull: %v", err)
	}
	if err := d.FillHSV(0.0, 1.0, 1.0); err != nil {
		t.Fatalf("FillHSV: %v", err)
	}
	want := []byte{0, 255, 0} // g=0, r=255, b=0
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("FillHSV(red) write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestWS2812BMaxPixelsClamp(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BMinimal(conn, MaxPixelsWS2812B+50)
	if err != nil {
		t.Fatalf("NewWS2812BMinimal: %v", err)
	}
	if d.n != MaxPixelsWS2812B {
		t.Errorf("n = %v, want %v", d.n, MaxPixelsWS2812B)
	}
}

func TestWS2812BNegativePixelsClamp(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2812BMinimal(conn, -5)
	if err != nil {
		t.Fatalf("NewWS2812BMinimal: %v", err)
	}
	if d.n != 0 {
		t.Errorf("n = %v, want 0", d.n)
	}
}
