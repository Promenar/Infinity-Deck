#ifndef NX_BRIDGE_PROTOCOL_H
#define NX_BRIDGE_PROTOCOL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <poll.h>
#include <sys/stat.h>

enum {
    NX_FRAME_STDIN = 0,
    NX_FRAME_STDOUT = 1,
    NX_FRAME_STDERR = 2,
    NX_FRAME_EXIT = 3,
    NX_FRAME_EOF = 4,
    NX_FRAME_SIGNAL = 5,
    NX_FRAME_RESIZE = 6,
};

struct nx_buffer {
    char *data;
    size_t capacity;
    size_t length;
    bool invalid;
};

size_t nx_length(const char *text);
bool nx_equal(const char *a, const char *b);
bool nx_prefix(const char *text, const char *prefix);
const char *nx_env(char *const env[], const char *key);
void nx_append(struct nx_buffer *buffer, const char *text);
void nx_json_string(struct nx_buffer *buffer, const char *text, size_t length);
void nx_number(struct nx_buffer *buffer, unsigned value);
void nx_be32(unsigned char bytes[4], uint32_t value);
bool nx_send_header(int socket, const struct nx_buffer *request);
bool nx_accept_reply(int socket);
int nx_relay(int socket, int failure_status, bool map_negative_exit);
void nx_error(const char *message);

// 平台接口统一返回负 errno。socket I/O 在帧转发阶段不得阻塞。
bool nx_nonblocking_socket(int fd);
bool nx_stat(int fd, struct stat *state);
bool nx_is_tty(int fd);
#ifdef __linux__
int nx_open_nonblocking(const char *path, int original);
void nx_close(int fd);
#endif
long nx_read(int fd, void *data, size_t size);
long nx_write(int fd, const void *data, size_t size);
long nx_send(int socket, const void *data, size_t size, bool nonblocking);
long nx_receive(int socket, void *data, size_t size);
long nx_poll(struct pollfd *fds, size_t count);
unsigned nx_pending_control(unsigned char payload[4]);

#endif
