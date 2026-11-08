#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define DEVICE_NAME "TVDash-uinput-enter-hold-test"
#define READY_DELAY_MS 1200
#define HOLD_DURATION_MS 1200
#define RELEASE_SETTLE_MS 150
#define WATCHDOG_SECONDS 6

static volatile sig_atomic_t g_stop_signal = 0;
static int g_fd = -1;
static int g_device_created = 0;
static int g_key_may_be_down = 0;

static void request_stop(int signo) {
    if (g_stop_signal == 0) {
        g_stop_signal = signo;
    }
}

static int install_signal_handlers(void) {
    const int signals[] = { SIGINT, SIGTERM, SIGHUP, SIGALRM };
    struct sigaction action;

    memset(&action, 0, sizeof(action));
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    action.sa_flags = 0;

    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
        if (sigaction(signals[i], &action, NULL) < 0) {
            perror("sigaction");
            return -1;
        }
    }
    return 0;
}

static int64_t monotonic_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) {
        return -1;
    }
    return ((int64_t)now.tv_sec * INT64_C(1000000000)) + now.tv_nsec;
}

static void log_state(const char *state) {
    int64_t now = monotonic_ns();
    if (now >= 0) {
        printf("STATE=%s MONOTONIC_NS=%" PRId64 "\n", state, now);
    } else {
        printf("STATE=%s CLOCK_ERROR=%d\n", state, errno);
    }
    fflush(stdout);
}

static int wait_until_ns(int64_t deadline_ns) {
    struct timespec deadline;
    deadline.tv_sec = (time_t)(deadline_ns / INT64_C(1000000000));
    deadline.tv_nsec = (long)(deadline_ns % INT64_C(1000000000));

    while (g_stop_signal == 0) {
        int rc = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &deadline, NULL);
        if (rc == 0) {
            return 0;
        }
        if (rc == EINTR) {
            continue;
        }
        errno = rc;
        return -1;
    }

    errno = EINTR;
    return -1;
}

static int wait_ms(unsigned int duration_ms) {
    int64_t start = monotonic_ns();
    if (start < 0) {
        return -1;
    }
    return wait_until_ns(start + ((int64_t)duration_ms * INT64_C(1000000)));
}

/*
 * Send exactly one EV_KEY transition followed by SYN_REPORT.
 * No user-space repeat events are sent; Android InputDispatcher is expected
 * to synthesize repeats because this device reports HandlesKeyRepeat=false.
 */
static int send_enter_transition(int value) {
    struct input_event events[2];
    struct timeval timestamp;

    if (g_fd < 0 || !g_device_created) {
        errno = EBADF;
        return -1;
    }

    if (gettimeofday(&timestamp, NULL) < 0) {
        return -1;
    }

    memset(events, 0, sizeof(events));
    events[0].time = timestamp;
    events[0].type = EV_KEY;
    events[0].code = KEY_ENTER;
    events[0].value = value;

    events[1].time = timestamp;
    events[1].type = EV_SYN;
    events[1].code = SYN_REPORT;
    events[1].value = 0;

    ssize_t written;
    do {
        written = write(g_fd, events, sizeof(events));
    } while (written < 0 && errno == EINTR && g_stop_signal == 0);

    if (written < 0) {
        return -1;
    }
    if ((size_t)written != sizeof(events)) {
        errno = EIO;
        return -1;
    }
    return 0;
}

/*
 * Best-effort release before device destruction. Destroying/closing the
 * uinput device is the final kernel-level cleanup if this write fails.
 */
static int cleanup_device(void) {
    int cleanup_failed = 0;

    if (g_key_may_be_down && g_fd >= 0 && g_device_created) {
        log_state("CLEANUP_KEY_UP_BEGIN");
        if (send_enter_transition(0) == 0) {
            g_key_may_be_down = 0;
            log_state("CLEANUP_KEY_UP_ACCEPTED");
        } else {
            fprintf(stderr, "CLEANUP_KEY_UP_FAILED errno=%d (%s)\n",
                    errno, strerror(errno));
            cleanup_failed = 1;
        }
    }

    if (g_device_created && g_fd >= 0) {
        if (ioctl(g_fd, UI_DEV_DESTROY) == 0) {
            g_device_created = 0;
            log_state("DEVICE_DESTROYED");
        } else {
            fprintf(stderr, "UI_DEV_DESTROY_FAILED errno=%d (%s)\n",
                    errno, strerror(errno));
            cleanup_failed = 1;
        }
    }

    if (g_fd >= 0) {
        if (close(g_fd) < 0) {
            fprintf(stderr, "CLOSE_FAILED errno=%d (%s)\n", errno, strerror(errno));
            cleanup_failed = 1;
        } else {
            log_state("UINPUT_FD_CLOSED");
        }
        g_fd = -1;
        g_device_created = 0;
    }

    return cleanup_failed ? -1 : 0;
}

int main(void) {
    int result = 1;
    int64_t down_accepted_ns = -1;
    int64_t up_begin_ns = -1;

    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("PROBE=UINPUT_ENTER_HOLD ABI_TARGET=armeabi-v7a KEY=KEY_ENTER CODE=%d HOLD_MS=%d\n",
           KEY_ENTER, HOLD_DURATION_MS);

    if (install_signal_handlers() < 0) {
        return 1;
    }

    g_fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (g_fd < 0) {
        perror("open /dev/uinput");
        return 1;
    }
    log_state("UINPUT_OPEN");

    if (ioctl(g_fd, UI_SET_EVBIT, EV_KEY) < 0) {
        perror("UI_SET_EVBIT");
        goto finish;
    }
    if (ioctl(g_fd, UI_SET_KEYBIT, KEY_ENTER) < 0) {
        perror("UI_SET_KEYBIT KEY_ENTER");
        goto finish;
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x5444;
    setup.id.product = 0x8766;
    setup.id.version = 1;
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "%s", DEVICE_NAME);

    if (ioctl(g_fd, UI_DEV_SETUP, &setup) < 0) {
        perror("UI_DEV_SETUP");
        goto finish;
    }
    if (ioctl(g_fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE");
        goto finish;
    }
    g_device_created = 1;
    log_state("DEVICE_CREATED");

    alarm(WATCHDOG_SECONDS);
    printf("READY_DELAY_MS=%d; inspect dumpsys input before the Enter press\n",
           READY_DELAY_MS);
    if (wait_ms(READY_DELAY_MS) < 0) {
        fprintf(stderr, "READY_DELAY_INTERRUPTED signal=%d errno=%d\n",
                (int)g_stop_signal, errno);
        goto finish;
    }
    log_state("DEVICE_READY");

    if (g_stop_signal != 0) {
        fprintf(stderr, "STOP_REQUESTED_BEFORE_KEY_DOWN signal=%d\n",
                (int)g_stop_signal);
        goto finish;
    }

    /* Mark uncertain state before writing so every ambiguous failure attempts KEY_UP. */
    g_key_may_be_down = 1;
    log_state("KEY_DOWN_BEGIN");
    if (send_enter_transition(1) < 0) {
        fprintf(stderr, "KEY_DOWN_WRITE_FAILED errno=%d (%s)\n", errno, strerror(errno));
        goto finish;
    }
    down_accepted_ns = monotonic_ns();
    if (down_accepted_ns < 0) {
        perror("clock_gettime after KEY_DOWN");
        goto finish;
    }
    log_state("KEY_DOWN_ACCEPTED");

    if (wait_until_ns(down_accepted_ns +
                      ((int64_t)HOLD_DURATION_MS * INT64_C(1000000))) < 0) {
        fprintf(stderr, "HOLD_INTERRUPTED signal=%d errno=%d\n",
                (int)g_stop_signal, errno);
        goto finish;
    }

    up_begin_ns = monotonic_ns();
    if (up_begin_ns < 0) {
        perror("clock_gettime before KEY_UP");
        goto finish;
    }

    int64_t held_ms = (up_begin_ns - down_accepted_ns) / INT64_C(1000000);
    printf("HOLD_COMPLETE REQUESTED_MS=%d ACTUAL_MS=%" PRId64 "\n",
           HOLD_DURATION_MS, held_ms);
    if (held_ms < HOLD_DURATION_MS) {
        fprintf(stderr, "HOLD_DURATION_UNDERRUN requested=%d actual=%" PRId64 "\n",
                HOLD_DURATION_MS, held_ms);
        goto finish;
    }

    log_state("KEY_UP_BEGIN");
    if (send_enter_transition(0) < 0) {
        fprintf(stderr, "KEY_UP_WRITE_FAILED errno=%d (%s)\n", errno, strerror(errno));
        goto finish;
    }
    g_key_may_be_down = 0;
    log_state("KEY_UP_ACCEPTED");

    if (wait_ms(RELEASE_SETTLE_MS) < 0) {
        fprintf(stderr, "RELEASE_SETTLE_INTERRUPTED signal=%d errno=%d\n",
                (int)g_stop_signal, errno);
        goto finish;
    }

    result = 0;

finish:
    alarm(0);
    if (cleanup_device() < 0) {
        result = 1;
    }
    if (g_stop_signal != 0) {
        fprintf(stderr, "STOP_SIGNAL=%d; probe did not complete normally\n",
                (int)g_stop_signal);
        result = 1;
    }
    if (result == 0) {
        log_state("UINPUT_ENTER_HOLD_TEST_PASS");
    } else {
        log_state("UINPUT_ENTER_HOLD_TEST_FAIL");
    }
    return result;
}
