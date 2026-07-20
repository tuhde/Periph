#pragma once
#include <zephyr/kernel.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/drivers/gpio.h>
#include <cstring>
#include "Transport.h"

/** @brief UART transport for Zephyr RTOS (interrupt-driven UART API).
 *
 * Uses the Zephyr interrupt-driven UART API to implement non-blocking TX
 * with TX-complete signalling via a semaphore, and RX accumulation into a
 * small ring buffer that the ISR drains continuously, whether or not a
 * read() call is currently outstanding. This avoids losing bytes (or
 * re-triggering the RX-ready interrupt indefinitely) between read() calls,
 * and lets available() report how many bytes are already buffered without
 * blocking.
 *
 * prj.conf must enable:
 *   CONFIG_UART_INTERRUPT_DRIVEN=y
 *   CONFIG_GPIO=y              (RS-485 only)
 *   CONFIG_CPP=y
 *   CONFIG_STD_CPP17=y
 *
 * The UART device node must be present and enabled in the board's devicetree
 * or an overlay.
 *
 * @param dev    UART device pointer obtained via DEVICE_DT_GET().
 * @param de_gpio RS-485 DE GPIO spec from GPIO_DT_SPEC_GET(); zero-init
 *               (port == NULL) disables RS-485 mode.
 */
class UARTTransportZephyr : public Transport {
public:
    UARTTransportZephyr(const struct device* dev,
                        const struct gpio_dt_spec& de_gpio = {})
        : _dev(dev), _de_gpio(de_gpio)
    {
        k_sem_init(&_tx_done, 0, 1);
        uart_irq_callback_user_data_set(_dev, _uart_isr, this);
        uart_irq_rx_enable(_dev);

        if (_de_gpio.port != nullptr)
            gpio_pin_configure_dt(&_de_gpio, GPIO_OUTPUT_INACTIVE);
    }

    /** @brief Transmit bytes; in RS-485 mode assert DE, fill TX FIFO via IRQ,
     *         wait for TX-complete semaphore, then deassert DE.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override {
        _tx_buf     = data;
        _tx_len     = len;
        _tx_pos     = 0;
        _tx_running = true;

        if (_de_gpio.port != nullptr)
            gpio_pin_set_dt(&_de_gpio, 1);

        uart_irq_tx_enable(_dev);
        k_sem_take(&_tx_done, K_FOREVER);

        if (_de_gpio.port != nullptr)
            gpio_pin_set_dt(&_de_gpio, 0);
    }

    /** @brief Receive @p len bytes from the RX ring buffer; blocks (sleeping
     *         1 ms between polls) until all bytes have arrived or a 1 s
     *         total timeout expires. On timeout, fewer than @p len bytes may
     *         have been written to @p buf — call available() to check how
     *         many bytes are buffered before deciding how much of @p buf is
     *         valid.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override {
        size_t got = 0;
        int64_t deadline = k_uptime_get() + 1000;
        while (got < len) {
            if (_ring_tail != _ring_head) {
                buf[got++] = _ring[_ring_tail];
                _ring_tail = (_ring_tail + 1) % RING_SIZE;
            } else if (k_uptime_get() >= deadline) {
                break;
            } else {
                k_sleep(K_MSEC(1));
            }
        }
    }

    /** @brief Number of bytes currently buffered in the RX ring, available to
     *         read() without blocking.
     *  @return Byte count currently queued.
     */
    size_t available() const override {
        size_t head = _ring_head, tail = _ring_tail;
        return (head >= tail) ? (head - tail) : (RING_SIZE - tail + head);
    }

    /** @brief Transmit bytes then receive @p buf_len bytes.
     *
     *  In RS-485 mode DE is asserted only during the transmit phase.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        write(data, data_len);
        read(buf, buf_len);
    }

private:
    const struct device*   _dev;
    const struct gpio_dt_spec _de_gpio;

    struct k_sem _tx_done;

    volatile const uint8_t* _tx_buf     = nullptr;
    volatile size_t          _tx_len     = 0;
    volatile size_t          _tx_pos     = 0;
    volatile bool            _tx_running = false;

    static constexpr size_t RING_SIZE = 256;
    uint8_t         _ring[RING_SIZE];
    volatile size_t _ring_head = 0;  // next write index, touched only by the ISR
    volatile size_t _ring_tail = 0;  // next read index, touched only by the consumer

    static void _uart_isr(const struct device* dev, void* user_data) {
        auto* self = static_cast<UARTTransportZephyr*>(user_data);

        if (uart_irq_update(dev) < 0) return;

        if (uart_irq_tx_ready(dev) && self->_tx_running) {
            if (self->_tx_pos < self->_tx_len) {
                int sent = uart_fifo_fill(dev,
                    const_cast<const uint8_t*>(self->_tx_buf) + self->_tx_pos,
                    static_cast<int>(self->_tx_len - self->_tx_pos));
                if (sent > 0)
                    self->_tx_pos += static_cast<size_t>(sent);
            }
            if (self->_tx_pos >= self->_tx_len && uart_irq_tx_complete(dev)) {
                uart_irq_tx_disable(dev);
                self->_tx_running = false;
                k_sem_give(&self->_tx_done);
            }
        }

        if (uart_irq_rx_ready(dev)) {
            uint8_t byte;
            while (uart_fifo_read(dev, &byte, 1) == 1) {
                size_t next = (self->_ring_head + 1) % RING_SIZE;
                if (next != self->_ring_tail) {           // drop byte on overflow rather than corrupt the ring
                    self->_ring[self->_ring_head] = byte;
                    self->_ring_head = next;
                }
            }
        }
    }
};
