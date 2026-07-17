#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "UARTTransportZephyr.h"
#include "NEO6.h"

#ifndef NEO6_UART_NODE
#define NEO6_UART_NODE DT_NODELABEL(uart1)
#endif

int main(void) {
    const struct device* dev = DEVICE_DT_GET(NEO6_UART_NODE);
    UARTTransportZephyr transport(dev);
    NEO6Full gps(transport);                             // Create NEO-6 driver, (transport, bus_type=Uart)

    gps.setRate(1);                                      // Set navigation update rate, (hz) → void
                                                          // writes CFG-RATE with measRate = 1000/hz ms
    gps.setPlatform(0);                                   // Set dynamic platform model, (model 0-8) → void
                                                          // writes CFG-NAV5 with mask=dynModel only
    gps.saveConfig();                                     // Persist current configuration, () → void
                                                          // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

    while (true) {
        if (gps.update()) {                              // Read + parse one NMEA sentence, () → bool
            printk("lat=%d lon=%d alt=%d\n",
                   (int)gps.latitude(), (int)gps.longitude(), (int)gps.altitude());
                                                          // decimal degrees, decimal degrees, meters MSL
            printk("speed=%d course=%d\n", (int)gps.speed(), (int)gps.course());
            printk("time=%s date=%s hdop=%d\n", gps.utcTime(), gps.utcDate(), (int)gps.hdop());

            uint8_t payload[256];
            size_t payloadLen = 0;
            if (gps.pollUbx(0x01, 0x03, payload, payloadLen, sizeof(payload))) {  // Poll a UBX message, (msg_class, msg_id, out_payload, out_len, max_len) → bool
                printk("NAV-STATUS payload bytes: %d\n", (int)payloadLen);
            }

            gps.coldStart();                              // Force a cold start via CFG-RST, () → void
        }
        k_msleep(50);
    }
    return 0;
}
