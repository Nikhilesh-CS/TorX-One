#ifndef TORX_RADIO_PROTOCOL_H
#define TORX_RADIO_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#define TORX_RADIO_MAGIC 0x54585231u
#define TORX_RADIO_VERSION 1u
#define TORX_RADIO_MAX_PAYLOAD 480u
#define TORX_RADIO_HEADER_BYTES 12u
#define TORX_RADIO_CRC_BYTES 4u
#define TORX_RADIO_SERVICE_UUID "7e582001-7c69-4f72-9858-746f72786f6e"
#define TORX_RADIO_CONTROL_UUID "7e582002-7c69-4f72-9858-746f72786f6e"
#define TORX_RADIO_PHONE_TX_UUID "7e582003-7c69-4f72-9858-746f72786f6e"
#define TORX_RADIO_PHONE_RX_UUID "7e582004-7c69-4f72-9858-746f72786f6e"

typedef enum {
    TORX_HELLO_REQUEST = 1, TORX_HELLO_RESPONSE = 2, TORX_SEND_PACKET = 3,
    TORX_RECEIVED_PACKET = 4, TORX_TX_RESULT = 5, TORX_STATUS_REQUEST = 6,
    TORX_STATUS_RESPONSE = 7, TORX_PING = 8, TORX_PONG = 9
} torx_radio_kind_t;

/* Implementations must parse explicit bytes; do not cast packed network input to a C struct. */
typedef struct {
    torx_radio_kind_t kind;
    uint32_t sequence;
    uint16_t payload_length;
    const uint8_t *payload;
} torx_radio_frame_view_t;

uint32_t torx_radio_crc32(const uint8_t *data, size_t length);
int torx_radio_decode(const uint8_t *data, size_t length, torx_radio_frame_view_t *out);

#endif
