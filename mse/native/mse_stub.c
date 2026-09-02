#include "mse_native.h"
#include <stddef.h>

void *mse_host_create(void)
{
    return NULL;
}

void mse_host_destroy(void *h)
{
    (void)h;
}

const void *mse_host_vtable(void)
{
    return NULL;
}

int mse_host_frames(void *h)
{
    (void)h;
    return 0;
}

int mse_host_copy_frame(void *h, uint8_t *dst, int cap, int *w, int *ht, int *gen)
{
    (void)h;
    (void)dst;
    (void)cap;
    if (w)
        *w = 0;
    if (ht)
        *ht = 0;
    if (gen)
        *gen = 0;
    return 0;
}
