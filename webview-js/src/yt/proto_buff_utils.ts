import { ProtobufField, WireTypes } from '../protobuf/common';
import { ProtobufDecoder } from '../protobuf/protobuf_decoder';
import { ProtobufEncoder } from '../protobuf/protobuf_encoder';
import { YMixWebViewApi } from '../webview_api';
import { u8ToBase64, utf8ToU8 } from './utils';

function pickAnyAdaptiveVideoFormat(playerRes: any): { itag: number; lmt: number; xtags?: string } | undefined {
  let selected: any = undefined;
  const adaptiveFormats = playerRes?.streamingData?.adaptiveFormats || [];
  for (let i = 0; i < adaptiveFormats.length; i++) {
    const adaptiveFormat = adaptiveFormats[i];
    if (adaptiveFormat.height > 0) {
      if (selected == null || selected.height > adaptiveFormat.height) {
        selected = adaptiveFormat;
      }
    }
  }
  if (selected) {
    return {
      itag: parseInt(selected.itag),
      lmt: parseInt(selected.lastModified),
      xtags: selected.xtags,
    };
  }
}

let moviePlayer: any;

export function processSabrRequestBody(wvApi: YMixWebViewApi, sabrUrl: string, requestData: Uint8Array, onBodyTransformed?: (newBody: Uint8Array) => void, onPoTokenDetected?: (poToken: string) => void) {
  if (!onBodyTransformed && !onPoTokenDetected) return;
  if (DEBUG) console.log('Before', ProtobufDecoder.toJson(new ProtobufDecoder(requestData, 0).readAllField()));
  window.handleYtInternalLog?.('info', 'sabr_request', () => u8ToBase64(requestData));
  let rootFields = new ProtobufDecoder(requestData, 0).readAllField();
  if (onBodyTransformed) {
    // filter selected videos field which has itag = 0
    rootFields = rootFields.filter((field) => {
      if (field.fieldNumber != 17) return true;
      const itagField = new ProtobufDecoder(field.value as Uint8Array, 0).readAllField()
        .find(field => field.fieldNumber == 1);
      return itagField?.value !== 0;
    });

    const clientAbrStateFieldRaw = rootFields.find(field => field.fieldNumber == 1);
    if (clientAbrStateFieldRaw) {
      let clientAbrStateFields = new ProtobufDecoder(clientAbrStateFieldRaw.value as Uint8Array, 0).readAllField();
      moviePlayer = moviePlayer || document.getElementById('movie_player') as any;
      const userPlaybackQualityPreference = moviePlayer?.getUserPlaybackQualityPreference();
      window.handleYtInternalLog?.('info', 'sabr_request', `userPlaybackQualityPreference=${userPlaybackQualityPreference}`);
      const isAudioOnlyRequested = userPlaybackQualityPreference === 'zero';
      if (!isAudioOnlyRequested) {
        clientAbrStateFields
          = clientAbrStateFields.filter(field => field.fieldNumber != 16 || field.value != 1);
      }

      if (isAudioOnlyRequested) {
        clientAbrStateFields = clientAbrStateFields.filter(it => it.fieldNumber != 40 && it.fieldNumber != 46);
        clientAbrStateFields.push(new ProtobufField(40, WireTypes.VAR_INT, 1));
        clientAbrStateFields.push(new ProtobufField(46, WireTypes.VAR_INT, 1));
      }
      clientAbrStateFieldRaw.value = ProtobufEncoder.encode(clientAbrStateFields);
    }
    //
    const urlId = sabrUrl && /[?&]id=([\w-]+)/.exec(sabrUrl)?.[1];
    const playerRes = urlId && wvApi.findPlayerResponse((videoId: string, playerRes: any): boolean => {
      return playerRes?.streamingData?.serverAbrStreamingUrl?.includes(urlId);
    });

    // if (playerRes && rootFields.find(it => it.fieldNumber == 17) == null) {
    // must have at least 1 video format
    // const videoFormat = pickAnyAdaptiveVideoFormat(playerRes);
    // if (videoFormat) {
    //   const selectedVideos = ProtobufEncoder.encode([
    //     new ProtobufField(1, WireTypes.VAR_INT, videoFormat.itag),
    //     new ProtobufField(2, WireTypes.VAR_INT, videoFormat.lmt),
    //     new ProtobufField(3, WireTypes.FIXED_LENGTH, videoFormat.xtags ? utf8ToU8(videoFormat.xtags) : new Uint8Array(0)),
    //   ]);
    //   rootFields.push(new ProtobufField(17, WireTypes.FIXED_LENGTH, selectedVideos));
    // }
    // }

    if (playerRes) {
      (playerRes.streamingData?.adaptiveFormats || []).forEach((format: any) => {
        rootFields.push(
          new ProtobufField(568, WireTypes.FIXED_LENGTH, ProtobufEncoder.encode([
            new ProtobufField(1, WireTypes.VAR_INT, parseInt(format.itag)),
            new ProtobufField(2, WireTypes.VAR_INT, parseInt(format.lastModified)),
            new ProtobufField(3, WireTypes.FIXED_LENGTH, format.xtags ? utf8ToU8(format.xtags) : new Uint8Array(0)),
            new ProtobufField(4, WireTypes.FIXED_LENGTH, utf8ToU8(format.mimeType)),
            new ProtobufField(5, WireTypes.VAR_INT, parseInt(format.contentLength)),
            new ProtobufField(6, WireTypes.VAR_INT, parseInt(format.indexRange?.start ?? 0)), // null for live video
            new ProtobufField(7, WireTypes.VAR_INT, parseInt(format.indexRange?.end ?? 0)),
            new ProtobufField(8, WireTypes.VAR_INT, format.width ?? 0),
            new ProtobufField(9, WireTypes.VAR_INT, format.height ?? 0),
            new ProtobufField(10, WireTypes.VAR_INT, parseInt(format.approxDurationMs)),
            ...(format.audioTrack
              ? [
                  new ProtobufField(11, WireTypes.VAR_INT, format.audioTrack.audioIsDefault ? 1 : 0),
                  new ProtobufField(12, WireTypes.FIXED_LENGTH, utf8ToU8(format.audioTrack.id ?? '')),
                ]
              : []),
            new ProtobufField(13, WireTypes.VAR_INT, format.isDrc ? 1 : 0),
          ])),
        );
      });
      rootFields.push(
        new ProtobufField(569, WireTypes.FIXED_LENGTH, utf8ToU8(playerRes.videoDetails.videoId)),
      );
    }
    requestData = ProtobufEncoder.encode(rootFields);
    onBodyTransformed(requestData);
  }

  if (DEBUG) console.log('After', ProtobufDecoder.toJson(new ProtobufDecoder(requestData, 0).readAllField()));

  if (onPoTokenDetected) {
    const streamerContext = rootFields.find(it => it.fieldNumber == 19);
    if (streamerContext?.value instanceof Uint8Array) {
      const streamerContextFields = new ProtobufDecoder(streamerContext.value, 0).readAllField();
      const poTokenU8 = streamerContextFields.find(it => it.fieldNumber == 2);
      if (poTokenU8?.value instanceof Uint8Array) {
        onPoTokenDetected(u8ToBase64(poTokenU8.value, /* url safe */ true));
      }
    }
  }
}
