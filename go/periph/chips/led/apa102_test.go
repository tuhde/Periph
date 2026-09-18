// Unit tests for APA102 driver using an in-memory mock connection.
package led

import (
	"fmt"
	"testing"
)

// TestAPA102Full runs the same assertions as the Python/C++/Node.js unit tests.
// APA102 is write-only (no registers, no reads); reuses the shared
// mockConnection/newMockConnection from ws2812b_test.go.
func TestAPA102Full(t *testing.T) {
	passed := 0
	failed := 0

	checkTrue := func(label string, condition bool) {
		if condition {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}
	checkEq := func(label string, got, expected interface{}) {
		if fmt.Sprintf("%v", got) == fmt.Sprintf("%v", expected) {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Printf("FAIL %s: got %v, expected %v\n", label, got, expected)
			failed++
		}
	}

	const N = 4
	conn := newMockConnection()
	sensor, err := NewAPA102Full(conn, N)
	if err != nil {
		t.Fatal(err)
	}
	checkTrue("init", true)

	// Expected frame structure:
	// start_frame = bytes(4)                    # 0x00 × 4
	// pixel data: N × 4 bytes [0xE0|brightness, B, G, R]
	// end_frame = bytes([0xFF] * max(4, (N + 15) // 16))
	endBytes := (N + 15) / 16
	if endBytes < 4 {
		endBytes = 4
	}
	FRAME_LEN := 4 + N*4 + endBytes

	// fill(): BGR wire order with brightness byte, all N pixels, transmits immediately.
	sensor.Fill(0x11, 0x22, 0x33)
	checkTrue("fill_transmits", len(conn.writes) == 1)
	checkTrue("fill_length", len(conn.writes[len(conn.writes)-1]) == FRAME_LEN)

	// Check start frame (4 zero bytes)
	w := conn.writes[len(conn.writes)-1]
	checkTrue("fill_start_frame", w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0)

	// Check pixel data: [0xE0|31, B, G, R] = [0xFF, 0x33, 0x22, 0x11] per pixel
	pixelOk := true
	for i := 0; i < N; i++ {
		base := 4 + i*4
		if w[base] != 0xFF || w[base+1] != 0x33 || w[base+2] != 0x22 || w[base+3] != 0x11 {
			pixelOk = false
		}
	}
	checkTrue("fill_pixel_order", pixelOk)

	// Check end frame (all 0xFF)
	endOk := true
	for i := 0; i < endBytes; i++ {
		if w[4+N*4+i] != 0xFF {
			endOk = false
		}
	}
	checkTrue("fill_end_frame", endOk)

	// off(): equivalent to fill(0, 0, 0).
	sensor.Off()
	w = conn.writes[len(conn.writes)-1]
	offOk := true
	for i := 0; i < N; i++ {
		base := 4 + i*4
		if w[base] != 0xFF || w[base+1] != 0 || w[base+2] != 0 || w[base+3] != 0 {
			offOk = false
		}
	}
	checkTrue("off", offOk)

	// set_pixel(): buffer-only, no transmit.
	writesBefore := len(conn.writes)
	sensor.SetPixel(1, 0xAA, 0xBB, 0xCC, 31)
	checkTrue("set_pixel_no_transmit", len(conn.writes) == writesBefore)
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	// Pixel 1 should be [0xFF, 0xCC, 0xBB, 0xAA]
	checkTrue("set_pixel_then_show", w[4+4] == 0xFF && w[4+5] == 0xCC && w[4+6] == 0xBB && w[4+7] == 0xAA)
	// Pixel 0 should be [0xFF, 0, 0, 0]
	checkTrue("set_pixel_other_pixels_zero", w[4] == 0xFF && w[5] == 0 && w[6] == 0 && w[7] == 0)

	// set_pixel() clamps index to [0, n-1].
	sensor.SetPixel(99, 0x01, 0x02, 0x03, 31)
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	checkTrue("set_pixel_clamps_index",
		w[4+(N-1)*4] == 0xFF && w[4+(N-1)*4+1] == 0x03 && w[4+(N-1)*4+2] == 0x02 && w[4+(N-1)*4+3] == 0x01)

	// set_pixel() with custom pixel_brightness.
	sensor.SetPixel(0, 0x10, 0x20, 0x30, 16)
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	checkTrue("set_pixel_hardware_brightness", w[4] == (0xE0|16) && w[5] == 0x30 && w[6] == 0x20 && w[7] == 0x10)

	// set_pixels(): sequence of [r, g, b] or [r, g, b, brightness], extras beyond n ignored.
	sensor.SetPixels([][]uint8{
		{0x10, 0x20, 0x30}, {0x40, 0x50, 0x60}, {0x70, 0x80, 0x90}, {0xA0, 0xB0, 0xC0}, {0xFF, 0xFF, 0xFF},
	})
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	setPixelsOk := true
	if w[4] != 0xFF || w[5] != 0x30 || w[6] != 0x20 || w[7] != 0x10 {
		setPixelsOk = false
	}
	if w[8] != 0xFF || w[9] != 0x60 || w[10] != 0x50 || w[11] != 0x40 {
		setPixelsOk = false
	}
	if w[12] != 0xFF || w[13] != 0x90 || w[14] != 0x80 || w[15] != 0x70 {
		setPixelsOk = false
	}
	if w[16] != 0xFF || w[17] != 0xC0 || w[18] != 0xB0 || w[19] != 0xA0 {
		setPixelsOk = false
	}
	checkTrue("set_pixels", setPixelsOk)

	// set_pixels() with per-pixel brightness.
	sensor.SetPixels([][]uint8{
		{0x10, 0x20, 0x30, 31}, {0x40, 0x50, 0x60, 16}, {0x70, 0x80, 0x90, 8}, {0xA0, 0xB0, 0xC0, 4},
	})
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	setPixelsBrightOk := true
	if w[4] != 0xFF || w[5] != 0x30 || w[6] != 0x20 || w[7] != 0x10 {
		setPixelsBrightOk = false
	}
	if w[8] != 0xF0 || w[9] != 0x60 || w[10] != 0x50 || w[11] != 0x40 {
		setPixelsBrightOk = false
	}
	if w[12] != 0xE8 || w[13] != 0x90 || w[14] != 0x80 || w[15] != 0x70 {
		setPixelsBrightOk = false
	}
	if w[16] != 0xE4 || w[17] != 0xC0 || w[18] != 0xB0 || w[19] != 0xA0 {
		setPixelsBrightOk = false
	}
	checkTrue("set_pixels_hardware_brightness", setPixelsBrightOk)

	// brightness scaling at show() time: sent = stored * brightness / 255.
	// Hardware brightness byte is NOT scaled.
	sensor.SetBrightness(128)
	sensor.SetPixel(0, 200, 100, 50, 31) // stored: [0xFF, 50, 100, 200]
	sensor.SetPixel(1, 0, 0, 0, 31)
	sensor.SetPixel(2, 0, 0, 0, 31)
	sensor.SetPixel(3, 0, 0, 0, 31)
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	scaledR := uint8(uint16(200) * 128 / 255)
	scaledG := uint8(uint16(100) * 128 / 255)
	scaledB := uint8(uint16(50) * 128 / 255)
	checkTrue("brightness_scaling", w[4] == 0xFF && w[5] == scaledB && w[6] == scaledG && w[7] == scaledR)
	// Other pixels unchanged (still zero)
	checkTrue("brightness_other_pixels_zero", w[8] == 0xFF && w[9] == 0 && w[10] == 0 && w[11] == 0)
	checkEq("get_brightness", sensor.GetBrightness(), uint8(128))
	sensor.SetBrightness(255)

	// rotate(): shifts pixel buffer left by `steps` whole pixels, no transmit.
	sensor.SetPixel(0, 1, 0, 0, 31)
	sensor.SetPixel(1, 2, 0, 0, 31)
	sensor.SetPixel(2, 3, 0, 0, 31)
	sensor.SetPixel(3, 4, 0, 0, 31)
	sensor.Show()
	writesBefore = len(conn.writes)
	sensor.Rotate(1)
	checkTrue("rotate_no_transmit", len(conn.writes) == writesBefore)
	sensor.Show()
	w = conn.writes[len(conn.writes)-1]
	// After rotate(1): pixel 0 gets old pixel 1 (R=2), pixel 3 gets old pixel 0 (R=1)
	checkTrue("rotate_shifts_left", w[4+0+3] == 2 && w[4+(N-1)*4+3] == 1)

	// fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
	sensor.FillHSV(0.0, 1.0, 1.0)
	w = conn.writes[len(conn.writes)-1]
	// Red=255, Green=0, Blue=0 -> wire: [0xFF, 0, 0, 255]
	hsvOk := true
	for i := 0; i < N; i++ {
		base := 4 + i*4
		if w[base] != 0xFF || w[base+1] != 0 || w[base+2] != 0 || w[base+3] != 255 {
			hsvOk = false
		}
	}
	checkTrue("fill_hsv_red", hsvOk)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		t.Fail()
	}
}