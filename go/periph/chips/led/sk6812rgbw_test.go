package led

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockResetConnection is like mockConnection but also implements
// connection.ResetExtender, so tests can verify SK6812RGBW requests its
// extended reset pulse via WriteExt instead of falling back to plain Write.
type mockResetConnection struct {
	mockConnection
	extWrites     [][]byte
	extResetBytes []int
}

func newMockResetConnection() *mockResetConnection {
	return &mockResetConnection{}
}

func (m *mockResetConnection) WriteExt(data []byte, resetBytes int) error {
	cp := append([]byte(nil), data...)
	m.extWrites = append(m.extWrites, cp)
	m.extResetBytes = append(m.extResetBytes, resetBytes)
	return nil
}

var _ connection.ResetExtender = (*mockResetConnection)(nil)

func TestSK6812RGBWFillGRBWOrderAndClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWMinimal(conn, 2)
	if err != nil {
		t.Fatalf("NewSK6812RGBWMinimal: %v", err)
	}
	if err := d.Fill(10, 20, 30, 40); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{20, 10, 30, 40, 20, 10, 30, 40}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Fill write = % X, want % X", lastWrite(conn.extWrites), want)
	}
	if got := conn.extResetBytes[len(conn.extResetBytes)-1]; got != sk6812RgbwResetBytes {
		t.Errorf("Fill resetBytes = %v, want %v", got, sk6812RgbwResetBytes)
	}

	if err := d.Off(); err != nil {
		t.Fatalf("Off: %v", err)
	}
	want = []byte{0, 0, 0, 0, 0, 0, 0, 0}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Off write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestSK6812RGBWFillWhiteDefaultsZero(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWMinimal(conn, 1)
	if err != nil {
		t.Fatalf("NewSK6812RGBWMinimal: %v", err)
	}
	if err := d.Fill(0, 0, 0, 255); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{0, 0, 0, 255}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Fill(white) write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestSK6812RGBWSetPixelIndexClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWFull(conn, 3)
	if err != nil {
		t.Fatalf("NewSK6812RGBWFull: %v", err)
	}
	d.SetPixel(-1, 1, 2, 3, 4)
	d.SetPixel(1, 5, 6, 7, 8)
	d.SetPixel(100, 9, 10, 11, 12)
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	want := []byte{2, 1, 3, 4, 6, 5, 7, 8, 10, 9, 11, 12}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Show write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestSK6812RGBWSetPixelZeroLength(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWFull(conn, 0)
	if err != nil {
		t.Fatalf("NewSK6812RGBWFull: %v", err)
	}
	d.SetPixel(0, 1, 2, 3, 4) // must not panic on empty buffer
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
}

func TestSK6812RGBWBrightnessScaling(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWFull(conn, 1)
	if err != nil {
		t.Fatalf("NewSK6812RGBWFull: %v", err)
	}
	d.SetPixel(0, 200, 100, 50, 80)
	d.SetBrightness(128)
	if got := d.GetBrightness(); got != 128 {
		t.Errorf("GetBrightness() = %v, want 128", got)
	}
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	// stored order is GRBW: g=100, r=200, b=50, w=80; sent = stored*128/255
	want := []byte{
		uint8(uint16(100) * 128 / 255),
		uint8(uint16(200) * 128 / 255),
		uint8(uint16(50) * 128 / 255),
		uint8(uint16(80) * 128 / 255),
	}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Show (brightness) write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestSK6812RGBWRotateShiftsLeftByWholePixels(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWFull(conn, 4)
	if err != nil {
		t.Fatalf("NewSK6812RGBWFull: %v", err)
	}
	d.SetPixel(0, 1, 0, 0, 0)
	d.SetPixel(1, 2, 0, 0, 0)
	d.SetPixel(2, 3, 0, 0, 0)
	d.SetPixel(3, 4, 0, 0, 0)
	d.Rotate(1)
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	// pixels [1,2,3,4] rotated left by one -> [2,3,4,1], stored as (g,r,b,w)=(0,n,0,0)
	want := []byte{0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Rotate+Show write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestSK6812RGBWFillHSVRed(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWFull(conn, 1)
	if err != nil {
		t.Fatalf("NewSK6812RGBWFull: %v", err)
	}
	if err := d.FillHSV(0.0, 1.0, 1.0); err != nil {
		t.Fatalf("FillHSV: %v", err)
	}
	want := []byte{0, 255, 0, 0} // g=0, r=255, b=0, w=0
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("FillHSV(red) write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestSK6812RGBWMaxPixelsClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWMinimal(conn, MaxPixelsSK6812RGBW+50)
	if err != nil {
		t.Fatalf("NewSK6812RGBWMinimal: %v", err)
	}
	if d.n != MaxPixelsSK6812RGBW {
		t.Errorf("n = %v, want %v", d.n, MaxPixelsSK6812RGBW)
	}
}

func TestSK6812RGBWNegativePixelsClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewSK6812RGBWMinimal(conn, -5)
	if err != nil {
		t.Fatalf("NewSK6812RGBWMinimal: %v", err)
	}
	if d.n != 0 {
		t.Errorf("n = %v, want 0", d.n)
	}
}

// TestSK6812RGBWFallsBackToPlainWriteWithoutResetExtender verifies that when
// the connection does NOT implement connection.ResetExtender (e.g. TinyGo's
// ws2812-backed connection), sk6812rgbwWrite falls back to plain Write
// rather than failing or silently dropping the data.
func TestSK6812RGBWFallsBackToPlainWriteWithoutResetExtender(t *testing.T) {
	conn := newMockConnection()
	d, err := NewSK6812RGBWMinimal(conn, 1)
	if err != nil {
		t.Fatalf("NewSK6812RGBWMinimal: %v", err)
	}
	if err := d.Fill(1, 2, 3, 4); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{2, 1, 3, 4}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Fill write = % X, want % X", lastWrite(conn.writes), want)
	}
}
