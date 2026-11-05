#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { perror("open /dev/uinput"); return 1; }

    int created = 0;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) { perror("UI_SET_EVBIT"); goto fail; }
    if (ioctl(fd, UI_SET_KEYBIT, KEY_ENTER) < 0) { perror("UI_SET_KEYBIT"); goto fail; }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x5444;
    setup.id.product = 0x8765;
    setup.id.version = 1;
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "TVDash-uinput-probe");

    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
        perror("UI_DEV_SETUP");
        goto fail;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) { perror("UI_DEV_CREATE"); goto fail; }
    created = 1;
    puts("DEVICE_CREATED; inspect dumpsys input now; no key events are sent");
    fflush(stdout);
    sleep(10);

    if (ioctl(fd, UI_DEV_DESTROY) < 0) { perror("UI_DEV_DESTROY"); goto fail; }
    created = 0;
    puts("DEVICE_DESTROYED");
    if (close(fd) < 0) { perror("close"); return 1; }
    puts("UINPUT_PROBE_PASS");
    return 0;

fail:
    if (created && ioctl(fd, UI_DEV_DESTROY) < 0)
        fprintf(stderr, "cleanup UI_DEV_DESTROY failed: %s\n", strerror(errno));
    if (close(fd) < 0)
        fprintf(stderr, "cleanup close failed: %s\n", strerror(errno));
    return 1;
}
