#ifndef NX_BRIDGE_POSIX_H
#define NX_BRIDGE_POSIX_H

#include "bridge-protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

static int keep_standard_fds(int fd) {
    if (fd >= 0 && fd < 3) {
        int moved = fcntl(fd, F_DUPFD_CLOEXEC, 3);
        close(fd);
        return moved;
    }
    return fd;
}

static int nx_stream_socket(int family) {
    int fd = keep_standard_fds(socket(family, SOCK_STREAM, 0));
#ifdef SO_NOSIGPIPE
    int enabled = 1;
    if (fd >= 0 && setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) < 0) {
        close(fd);
        return -1;
    }
#endif
    return fd;
}

bool nx_nonblocking_socket(int fd) {
    int flags = fcntl(fd, F_GETFL);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

bool nx_stat(int fd, struct stat *state) {
    return fstat(fd, state) == 0;
}

bool nx_is_tty(int fd) {
    return isatty(fd) == 1;
}

#ifdef __linux__
int nx_open_nonblocking(const char *path, int original) {
    int flags = fcntl(original, F_GETFL);
    return flags < 0 ? -1 : keep_standard_fds(open(path, (flags & O_ACCMODE) | O_NONBLOCK | O_CLOEXEC | O_NOCTTY));
}

void nx_close(int fd) {
    close(fd);
}
#endif

long nx_read(int fd, void *data, size_t size) {
    ssize_t result = read(fd, data, size);
    return result < 0 ? -errno : result;
}

long nx_write(int fd, const void *data, size_t size) {
    ssize_t result = write(fd, data, size);
    return result < 0 ? -errno : result;
}

long nx_send(int socket, const void *data, size_t size, bool nonblocking) {
    int flags = nonblocking ? MSG_DONTWAIT : 0;
#ifdef MSG_NOSIGNAL
    flags |= MSG_NOSIGNAL;
#endif
    ssize_t result = send(socket, data, size, flags);
    return result < 0 ? -errno : result;
}

long nx_receive(int socket, void *data, size_t size) {
    ssize_t result = recv(socket, data, size, MSG_DONTWAIT);
    return result < 0 ? -errno : result;
}

#endif
