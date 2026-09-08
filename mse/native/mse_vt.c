#include "mse_host.h"
#include "qjs_mse_vt.h"

static const QjsMseVt kVt = {
    .current_time = (double (*)(void *))mse_host_current_time,
    .set_current_time = (void (*)(void *, double))mse_host_set_current_time,
    .duration = (double (*)(void *))mse_host_duration,
    .paused = (int (*)(void *))mse_host_paused,
    .ready_state = (int (*)(void *))mse_host_ready_state,
    .play = (void (*)(void *))mse_host_play,
    .pause = (void (*)(void *))mse_host_pause,
    .reset = (void (*)(void *))mse_host_reset,
    .append = (int (*)(void *, int, const uint8_t *, int, double, double, double))mse_host_append,
    .remove = (void (*)(void *, int, double, double))mse_host_remove,
    .abort = (void (*)(void *, int))mse_host_abort,
    .set_duration = (void (*)(void *, double))mse_host_set_duration,
    .buffered = (int (*)(void *, int, double *, int))mse_host_buffered,
    .video_size = (int (*)(void *, int *, int *))mse_host_video_size,
    .change_type = (void (*)(void *, int))mse_host_change_type,
    .rest_video = (void (*)(void *, int))mse_host_rest_video,
};

const void *mse_host_vtable(void)
{
    return &kVt;
}
