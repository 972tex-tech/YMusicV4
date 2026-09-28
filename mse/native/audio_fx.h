#ifndef YMUSIC_AUDIO_FX_H
#define YMUSIC_AUDIO_FX_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AFX_BANDS 10

typedef struct AudioFx AudioFx;

AudioFx *audio_fx_create(void);
void audio_fx_destroy(AudioFx *fx);
void audio_fx_reset(AudioFx *fx);

void audio_fx_set_rate(AudioFx *fx, int rate);
void audio_fx_set_volume(AudioFx *fx, float vol);
void audio_fx_set_eq(AudioFx *fx, const float bands_db[AFX_BANDS]);
void audio_fx_set_bass(AudioFx *fx, float db);
void audio_fx_set_virtualizer(AudioFx *fx, float width);
void audio_fx_set_speed(AudioFx *fx, float speed);
void audio_fx_set_pitch(AudioFx *fx, float pitch);

/* Same-length: EQ, bass, virtualizer, volume. frames = samples per channel. */
void audio_fx_color(AudioFx *fx, int16_t *s16, int frames, int ch);

/*
 * Tempo matches playbackRate / DAC clock. Pitch is a duration-preserving hop
 * resample. Returns output frames. speed=1,pitch=1 copies.
 */
int audio_fx_stretch(AudioFx *fx, const int16_t *in, int in_frames, int16_t *out, int out_cap, int ch);

#ifdef __cplusplus
}
#endif

#endif
