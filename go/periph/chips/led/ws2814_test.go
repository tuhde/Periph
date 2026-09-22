package led

import (
	"testing"
)

func TestWS2814FillRGBWIdentityOrderAndClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Minimal(conn, 2)
	if err != nil {
		t.Fatalf("NewWS2814Minimal: %v", err)
	}
	if err := d.Fill(10, 20, 30, 40); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{10, 20, 30, 40, 10, 20, 30, 40}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Fill write = % X, want % X", lastWrite(conn.extWrites), want)
	}
	if got := conn.extResetBytes[len(conn.extResetBytes)-1]; got != ws2814ResetBytes {
		t.Errorf("Fill resetBytes = %v, want %v", got, ws2814ResetBytes)
	}

	if err := d.Off(); err != nil {
		t.Fatalf("Off: %v", err)
	}
	want = []byte{0, 0, 0, 0, 0, 0, 0, 0}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Off write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestWS2814FillWhiteDefaultsZero(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Minimal(conn, 1)
	if err != nil {
		t.Fatalf("NewWS2814Minimal: %v", err)
	}
	if err := d.Fill(0, 0, 0, 255); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{0, 0, 0, 255}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Fill(white) write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestWS2814SetPixelIndexClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Full(conn, 3)
	if err != nil {
		t.Fatalf("NewWS2814Full: %v", err)
	}
	d.SetPixel(-1, 1, 2, 3, 4)
	d.SetPixel(1, 5, 6, 7, 8)
	d.SetPixel(100, 9, 10, 11, 12)
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	want := []byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Show write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestWS2814SetPixelZeroLength(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Full(conn, 0)
	if err != nil {
		t.Fatalf("NewWS2814Full: %v", err)
	}
	d.SetPixel(0, 1, 2, 3, 4) // must not panic on empty buffer
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
}

func TestWS2814BrightnessScaling(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Full(conn, 1)
	if err != nil {
		t.Fatalf("NewWS2814Full: %v", err)
	}
	d.SetPixel(0, 200, 100, 50, 80)
	d.SetBrightness(128)
	if got := d.GetBrightness(); got != 128 {
		t.Errorf("GetBrightness() = %v, want 128", got)
	}
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	// stored order is RGBW identity: r=200, g=100, b=50, w=80; sent = stored*128/255
	want := []byte{
		uint8(uint16(200) * 128 / 255),
		uint8(uint16(100) * 128 / 255),
		uint8(uint16(50) * 128 / 255),
		uint8(uint16(80) * 128 / 255),
	}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Show (brightness) write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestWS2814RotateShiftsLeftByWholePixels(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Full(conn, 4)
	if err != nil {
		t.Fatalf("NewWS2814Full: %v", err)
	}
	d.SetPixel(0, 1, 0, 0, 0)
	d.SetPixel(1, 2, 0, 0, 0)
	d.SetPixel(2, 3, 0, 0, 0)
	d.SetPixel(3, 4, 0, 0, 0)
	d.Rotate(1)
	if err := d.Show(); err != nil {
		t.Fatalf("Show: %v", err)
	}
	// pixels [1,2,3,4] rotated left by one -> [2,3,4,1], stored as (r,g,b,w)=(n,0,0,0)
	want := []byte{2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0}
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("Rotate+Show write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestWS2814FillHSVRed(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Full(conn, 1)
	if err != nil {
		t.Fatalf("NewWS2814Full: %v", err)
	}
	if err := d.FillHSV(0.0, 1.0, 1.0); err != nil {
		t.Fatalf("FillHSV: %v", err)
	}
	want := []byte{255, 0, 0, 0} // r=255, g=0, b=0, w=0
	if string(lastWrite(conn.extWrites)) != string(want) {
		t.Errorf("FillHSV(red) write = % X, want % X", lastWrite(conn.extWrites), want)
	}
}

func TestWS2814MaxPixelsClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Minimal(conn, MaxPixelsWS2814+50)
	if err != nil {
		t.Fatalf("NewWS2814Minimal: %v", err)
	}
	if d.n != MaxPixelsWS2814 {
		t.Errorf("n = %v, want %v", d.n, MaxPixelsWS2814)
	}
}

func TestWS2814NegativePixelsClamp(t *testing.T) {
	conn := newMockResetConnection()
	d, err := NewWS2814Minimal(conn, -5)
	if err != nil {
		t.Fatalf("NewWS2814Minimal: %v", err)
	}
	if d.n != 0 {
		t.Errorf("n = %v, want 0", d.n)
	}
}

// TestWS2814FallsBackToPlainWriteWithoutResetExtender verifies that when
// the connection does NOT implement connection.ResetExtender (e.g. TinyGo's
// ws2812-backed connection), the shared NeoPixel RGBW write helper falls
// back to plain Write rather than failing or silently dropping the data.
func TestWS2814FallsBackToPlainWriteWithoutResetExtender(t *testing.T) {
	conn := newMockConnection()
	d, err := NewWS2814Minimal(conn, 1)
	if err != nil {
		t.Fatalf("NewWS2814Minimal: %v", err)
	}
	if err := d.Fill(1, 2, 3, 4); err != nil {
		t.Fatalf("Fill: %v", err)
	}
	want := []byte{1, 2, 3, 4}
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("Fill write = % X, want % X", lastWrite(conn.writes), want)
	}
}
