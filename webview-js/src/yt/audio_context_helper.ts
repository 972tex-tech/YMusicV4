import { YMixWebViewApi } from '../webview_api';

export class AudioContextHelper {
  private initialized = false;
  private applyPitchFn?: (semitones: number) => void;
  private pitchFactor = 1;

  constructor(readonly element: HTMLAudioElement, readonly wvApi: YMixWebViewApi) {
  }

  private readonly init = () => {
    if (!this.initialized) {
      this.initialized = true;
      if (!window.AudioContext) return;

      const audioContext: AudioContext = new AudioContext({ latencyHint: 'playback' });
      if (!audioContext.audioWorklet) return;

      audioContext.audioWorklet.addModule('soundtouch.js')
        .then(() => {
          const soundtouchProcessor = new AudioWorkletNode(audioContext, 'soundtouch-processor');
          const pitchParam = soundtouchProcessor.parameters.get('pitch');
          if (!pitchParam) return;
          const mediaSource = audioContext.createMediaElementSource(this.element);
          let connected = false;
          this.applyPitchFn = (pitchFactor: number) => {
            if (pitchFactor == 1) {
              if (connected) {
                connected = false;
                mediaSource.disconnect();
                mediaSource.connect(audioContext.destination);
              }
              pitchParam.value = pitchFactor;
            } else {
              pitchParam.value = pitchFactor;
              if (!connected) {
                connected = true;
                mediaSource.disconnect();
                mediaSource.connect(soundtouchProcessor).connect(audioContext.destination);
              }
            }
          };
          this.applyPitchFn(this.pitchFactor);
        })
        .catch((error) => {
          this.wvApi.reportError('Init sound touch failure: ' + error.stack);
        });
    }
  };

  readonly setPitchFactor = (factor: number) => {
    if (this.pitchFactor != factor) {
      this.pitchFactor = factor;
      if (this.applyPitchFn) {
        this.applyPitchFn(factor);
      } else {
        this.init();
      }
    }
  };

  readonly setPitchSemitones = (semitones: number) => {
    this.setPitchFactor(Math.pow(2, semitones / 12));
  };
}
