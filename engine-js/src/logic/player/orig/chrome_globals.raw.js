/* Chrome's window interface set (Chrome 155, macOS: own [native code] constructors on window, in
 * Object.getOwnPropertyNames order), as "Name[:Parent[:length]]". BotGuard enumerates the globals and
 * probes rarer interfaces one at a time; every Chrome interface the env lacks gets an illegal-constructor
 * stub, and globals Chrome does not expose on youtube.com are removed. Runs after env.raw.js and
 * idl_stubs.raw.js, so existing constructors are kept as they are.
 */
(function () {
  var CHROME =
    "Object::1,Function::1,Array::1,Number::1,Boolean::1,String::1,Symbol,Date::7,Promise::1," +
    "RegExp::2,Error::1,AggregateError:Error:2,EvalError:Error:1,RangeError:Error:1," +
    "ReferenceError:Error:1,SyntaxError:Error:1,TypeError:Error:1,URIError:Error:1,ArrayBuffer::1," +
    "Uint8Array:TypedArray:3,Int8Array:TypedArray:3,Uint16Array:TypedArray:3,Int16Array:TypedArray:3," +
    "Uint32Array:TypedArray:3,Int32Array:TypedArray:3,BigUint64Array:TypedArray:3," +
    "BigInt64Array:TypedArray:3,Uint8ClampedArray:TypedArray:3,Float32Array:TypedArray:3," +
    "Float64Array:TypedArray:3,Float16Array:TypedArray:3,DataView::1,Map,BigInt::1,Set,Iterator," +
    "WeakMap,WeakSet,Proxy::2,FinalizationRegistry::1,WeakRef::1,Option,Image,Audio," +
    "WebKitMutationObserver::1,WebKitCSSMatrix:DOMMatrixReadOnly,FeaturePolicy,XPathResult," +
    "XPathExpression,XPathEvaluator,XMLSerializer,XMLHttpRequestUpload:XMLHttpRequestEventTarget," +
    "XMLHttpRequestEventTarget:EventTarget,XMLHttpRequest:XMLHttpRequestEventTarget," +
    "XMLDocument:Document,WritableStreamDefaultWriter::1,WritableStreamDefaultController," +
    "WritableStream,Worker:EventTarget:1,WindowControlsOverlayGeometryChangeEvent:Event:2," +
    "WindowControlsOverlay:EventTarget,Window:EventTarget,WheelEvent:MouseEvent:1," +
    "WebSocket:EventTarget:1,WebGLVertexArrayObject:WebGLObject,WebGLUniformLocation," +
    "WebGLTransformFeedback:WebGLObject,WebGLTexture:WebGLObject,WebGLSync:WebGLObject," +
    "WebGLShaderPrecisionFormat,WebGLShader:WebGLObject,WebGLSampler:WebGLObject," +
    "WebGLRenderingContext,WebGLRenderbuffer:WebGLObject,WebGLQuery:WebGLObject," +
    "WebGLProgram:WebGLObject,WebGLObject,WebGLFramebuffer:WebGLObject,WebGLContextEvent:Event:1," +
    "WebGLBuffer:WebGLObject,WebGLActiveInfo,WebGL2RenderingContext,WaveShaperNode:AudioNode:1," +
    "VisualViewport:EventTarget,VisibilityStateEntry:PerformanceEntry," +
    "VirtualKeyboardGeometryChangeEvent:Event:1,ViewTransitionTypeSet,ViewTransition," +
    "ViewTimeline:ScrollTimeline,VideoPlaybackQuality,VideoFrame::1,VideoColorSpace,ValidityState," +
    "VTTCue:TextTrackCue:3,UserActivation,URLSearchParams,URLPattern,URL::1,UIEvent:Event:1," +
    "TrustedTypePolicyFactory,TrustedTypePolicy,TrustedScriptURL,TrustedScript,TrustedHTML," +
    "TreeWalker,TransitionEvent:Event:1,TransformStreamDefaultController,TransformStream," +
    "TrackEvent:Event:1,TouchList,TouchEvent:UIEvent:1,Touch::1,ToggleEvent:Event:1,TimeRanges," +
    "TextUpdateEvent:Event:1,TextTrackList:EventTarget,TextTrackCueList,TextTrackCue:EventTarget," +
    "TextTrack:EventTarget,TextMetrics,TextFormatUpdateEvent:Event:1,TextFormat,TextEvent:UIEvent," +
    "TextEncoderStream,TextEncoder,TextDecoderStream,TextDecoder,Text:CharacterData," +
    "TaskSignal:AbortSignal,TaskPriorityChangeEvent:Event:2,TaskController:AbortController," +
    "TaskAttributionTiming:PerformanceEntry,SyncManager,Subscriber,SubmitEvent:Event:1," +
    "StyleSheetList,StyleSheet,StylePropertyMapReadOnly,StylePropertyMap:StylePropertyMapReadOnly," +
    "StorageEvent:Event:1,Storage,StereoPannerNode:AudioNode:1,StaticRange:NodeRange:1," +
    "SourceBufferList:EventTarget,SourceBuffer:EventTarget,ShadowRoot:DocumentFragment,Selection," +
    "SecurityPolicyViolationEvent:Event:1,ScrollTimeline:AnimationTimeline," +
    "ScriptProcessorNode:AudioNode,ScreenOrientation:EventTarget,Screen:EventTarget,Scheduling," +
    "Scheduler,SVGViewElement:SVGElement,SVGUseElement:SVGGraphicsElement,SVGUnitTypes," +
    "SVGTransformList,SVGTransform,SVGTitleElement:SVGElement," +
    "SVGTextPositioningElement:SVGTextContentElement,SVGTextPathElement:SVGTextContentElement," +
    "SVGTextElement:SVGTextPositioningElement,SVGTextContentElement:SVGGraphicsElement," +
    "SVGTSpanElement:SVGTextPositioningElement,SVGSymbolElement:SVGGraphicsElement," +
    "SVGSwitchElement:SVGGraphicsElement,SVGStyleElement:SVGElement,SVGStringList," +
    "SVGStopElement:SVGElement,SVGSetElement:SVGAnimationElement,SVGScriptElement:SVGElement," +
    "SVGSVGElement:SVGGraphicsElement,SVGRectElement:SVGGeometryElement,SVGRect," +
    "SVGRadialGradientElement:SVGGradientElement,SVGPreserveAspectRatio," +
    "SVGPolylineElement:SVGGeometryElement,SVGPolygonElement:SVGGeometryElement,SVGPointList," +
    "SVGPoint,SVGPatternElement:SVGElement,SVGPathElement:SVGGeometryElement,SVGNumberList,SVGNumber," +
    "SVGMetadataElement:SVGElement,SVGMatrix,SVGMaskElement:SVGElement,SVGMarkerElement:SVGElement," +
    "SVGMPathElement:SVGElement,SVGLinearGradientElement:SVGGradientElement," +
    "SVGLineElement:SVGGeometryElement,SVGLengthList,SVGLength,SVGImageElement:SVGGraphicsElement," +
    "SVGGraphicsElement:SVGElement,SVGGradientElement:SVGElement," +
    "SVGGeometryElement:SVGGraphicsElement,SVGGElement:SVGGraphicsElement," +
    "SVGForeignObjectElement:SVGGraphicsElement,SVGFilterElement:SVGElement," +
    "SVGFETurbulenceElement:SVGElement,SVGFETileElement:SVGElement,SVGFESpotLightElement:SVGElement," +
    "SVGFESpecularLightingElement:SVGElement,SVGFEPointLightElement:SVGElement," +
    "SVGFEOffsetElement:SVGElement,SVGFEMorphologyElement:SVGElement," +
    "SVGFEMergeNodeElement:SVGElement,SVGFEMergeElement:SVGElement,SVGFEImageElement:SVGElement," +
    "SVGFEGaussianBlurElement:SVGElement,SVGFEFuncRElement:SVGComponentTransferFunctionElement," +
    "SVGFEFuncGElement:SVGComponentTransferFunctionElement," +
    "SVGFEFuncBElement:SVGComponentTransferFunctionElement," +
    "SVGFEFuncAElement:SVGComponentTransferFunctionElement,SVGFEFloodElement:SVGElement," +
    "SVGFEDropShadowElement:SVGElement,SVGFEDistantLightElement:SVGElement," +
    "SVGFEDisplacementMapElement:SVGElement,SVGFEDiffuseLightingElement:SVGElement," +
    "SVGFEConvolveMatrixElement:SVGElement,SVGFECompositeElement:SVGElement," +
    "SVGFEComponentTransferElement:SVGElement,SVGFEColorMatrixElement:SVGElement," +
    "SVGFEBlendElement:SVGElement,SVGEllipseElement:SVGGeometryElement,SVGElement:Element," +
    "SVGDescElement:SVGElement,SVGDefsElement:SVGGraphicsElement," +
    "SVGComponentTransferFunctionElement:SVGElement,SVGClipPathElement:SVGElement," +
    "SVGCircleElement:SVGGeometryElement,SVGAnimationElement:SVGElement,SVGAnimatedTransformList," +
    "SVGAnimatedString,SVGAnimatedRect,SVGAnimatedPreserveAspectRatio,SVGAnimatedNumberList," +
    "SVGAnimatedNumber,SVGAnimatedLengthList,SVGAnimatedLength,SVGAnimatedInteger," +
    "SVGAnimatedEnumeration,SVGAnimatedBoolean,SVGAnimatedAngle," +
    "SVGAnimateTransformElement:SVGAnimationElement,SVGAnimateMotionElement:SVGAnimationElement," +
    "SVGAnimateElement:SVGAnimationElement,SVGAngle,SVGAElement:SVGGraphicsElement,Response," +
    "ResizeObserverSize,ResizeObserverEntry,ResizeObserver::1,Request::1,ReportingObserver::1," +
    "ReportBody,ReadableStreamDefaultReader::1,ReadableStreamDefaultController," +
    "ReadableStreamBYOBRequest,ReadableStreamBYOBReader::1,ReadableStream," +
    "ReadableByteStreamController,Range:NodeRange,RadioNodeList:NodeList,RTCTrackEvent:Event:2," +
    "RTCStatsReport,RTCSessionDescription,RTCSctpTransport:EventTarget,RTCRtpTransceiver," +
    "RTCRtpSender,RTCRtpReceiver,RTCPeerConnectionIceEvent:Event:1," +
    "RTCPeerConnectionIceErrorEvent:Event:2,RTCPeerConnection:EventTarget," +
    "RTCIceTransport:EventTarget,RTCIceCandidate,RTCErrorEvent:Event:2,RTCError:DOMException:1," +
    "RTCEncodedVideoFrame::1,RTCEncodedAudioFrame::1,RTCDtlsTransport:EventTarget," +
    "RTCDataChannelEvent:Event:2,RTCDTMFToneChangeEvent:Event:1,RTCDTMFSender:EventTarget," +
    "RTCCertificate,PromiseRejectionEvent:Event:2,ProgressEvent:Event:1," +
    "ProcessingInstruction:CharacterData,PopStateEvent:Event:1,PointerEvent:MouseEvent:1,PluginArray," +
    "Plugin,PictureInPictureWindow:EventTarget,PictureInPictureEvent:Event:2,PermissionsPolicy," +
    "Permissions,PermissionStatus:EventTarget,PeriodicWave::1,PerformanceTimingConfidence," +
    "PerformanceTiming,PerformanceSoftNavigation:PerformanceEntry,PerformanceServerTiming," +
    "PerformanceResourceTiming:PerformanceEntry,PerformancePaintTiming:PerformanceEntry," +
    "PerformanceObserverEntryList,PerformanceObserver::1," +
    "PerformanceNavigationTiming:PerformanceResourceTiming,PerformanceNavigation," +
    "PerformanceMeasure:PerformanceEntry,PerformanceMark:PerformanceEntry:1," +
    "PerformanceLongTaskTiming:PerformanceEntry,PerformanceEventTiming:PerformanceEntry," +
    "PerformanceEntry,PerformanceElementTiming:PerformanceEntry,Performance:EventTarget,Path2D," +
    "PannerNode:AudioNode:1,PageTransitionEvent:Event:1,PageRevealEvent:Event:1," +
    "OverconstrainedError:DOMException:1,OscillatorNode:AudioScheduledSourceNode:1," +
    "OffscreenCanvasRenderingContext2D,OffscreenCanvas:EventTarget:2," +
    "OfflineAudioContext:BaseAudioContext:1,OfflineAudioCompletionEvent:Event:2,Observable::1," +
    "NodeList,NodeIterator,NodeFilter,Node:EventTarget,NetworkInformation:EventTarget," +
    "NavigatorUAData,Navigator,NavigationTransition,NavigationPrecommitController," +
    "NavigationHistoryEntry:EventTarget,NavigationDestination," +
    "NavigationCurrentEntryChangeEvent:Event:2,NavigationActivation,Navigation:EventTarget," +
    "NavigateEvent:Event:2,NamedNodeMap,MutationRecord,MutationObserver::1,MouseEvent:UIEvent:1," +
    "MimeTypeArray,MimeType,MessagePort:EventTarget,MessageEvent:Event:1,MessageChannel," +
    "MediaStreamTrackVideoStats,MediaStreamTrackProcessor::1," +
    "MediaStreamTrackGenerator:MediaStreamTrack:1,MediaStreamTrackEvent:Event:2," +
    "MediaStreamTrackAudioStats,MediaStreamTrack:EventTarget,MediaStreamEvent:Event:1," +
    "MediaStreamAudioSourceNode:AudioNode:2,MediaStreamAudioDestinationNode:AudioNode:1," +
    "MediaStream:EventTarget,MediaSourceHandle,MediaSource:EventTarget,MediaRecorder:EventTarget:1," +
    "MediaQueryListEvent:Event:1,MediaQueryList:EventTarget,MediaList,MediaError," +
    "MediaEncryptedEvent:Event:1,MediaElementAudioSourceNode:AudioNode:2,MediaCapabilities," +
    "MathMLElement:Element,Location,LayoutShiftAttribution,LayoutShift:PerformanceEntry," +
    "LargestContentfulPaint:PerformanceEntry,KeyframeEffect:AnimationEffect:1," +
    "KeyboardEvent:UIEvent:1,IntersectionObserverEntry,IntersectionObserver::1,InterestEvent:Event:1," +
    "InteractionContentfulPaint:PerformanceEntry,InputEvent:UIEvent:1," +
    "InputDeviceInfo:MediaDeviceInfo,InputDeviceCapabilities,Ink,ImageData::2," +
    "ImageBitmapRenderingContext,ImageBitmap,IdleDeadline,IIRFilterNode:AudioNode:2," +
    "IDBVersionChangeEvent:Event:1,IDBTransaction:EventTarget,IDBRequest:EventTarget,IDBRecord," +
    "IDBOpenDBRequest:IDBRequest,IDBObjectStore,IDBKeyRange,IDBIndex,IDBFactory," +
    "IDBDatabase:EventTarget,IDBCursorWithValue:IDBCursor,IDBCursor,History,HighlightRegistry," +
    "Highlight,Headers,HashChangeEvent:Event:1,HTMLVideoElement:HTMLMediaElement," +
    "HTMLUnknownElement:HTMLElement,HTMLUListElement:HTMLElement,HTMLTrackElement:HTMLElement," +
    "HTMLTitleElement:HTMLElement,HTMLTimeElement:HTMLElement,HTMLTextAreaElement:HTMLElement," +
    "HTMLTemplateElement:HTMLElement,HTMLTableSectionElement:HTMLElement," +
    "HTMLTableRowElement:HTMLElement,HTMLTableElement:HTMLElement,HTMLTableColElement:HTMLElement," +
    "HTMLTableCellElement:HTMLElement,HTMLTableCaptionElement:HTMLElement," +
    "HTMLStyleElement:HTMLElement,HTMLSpanElement:HTMLElement,HTMLSourceElement:HTMLElement," +
    "HTMLSlotElement:HTMLElement,HTMLSelectedContentElement:HTMLElement," +
    "HTMLSelectElement:HTMLElement,HTMLScriptElement:HTMLElement,HTMLQuoteElement:HTMLElement," +
    "HTMLProgressElement:HTMLElement,HTMLPreElement:HTMLElement,HTMLPictureElement:HTMLElement," +
    "HTMLParamElement:HTMLElement,HTMLParagraphElement:HTMLElement,HTMLOutputElement:HTMLElement," +
    "HTMLOptionsCollection:HTMLCollection,HTMLOptionElement:HTMLElement," +
    "HTMLOptGroupElement:HTMLElement,HTMLObjectElement:HTMLElement,HTMLOListElement:HTMLElement," +
    "HTMLModElement:HTMLElement,HTMLMeterElement:HTMLElement,HTMLMetaElement:HTMLElement," +
    "HTMLMenuElement:HTMLElement,HTMLMediaElement:HTMLElement,HTMLMarqueeElement:HTMLElement," +
    "HTMLMapElement:HTMLElement,HTMLLinkElement:HTMLElement,HTMLLegendElement:HTMLElement," +
    "HTMLLabelElement:HTMLElement,HTMLLIElement:HTMLElement,HTMLInputElement:HTMLElement," +
    "HTMLImageElement:HTMLElement,HTMLIFrameElement:HTMLElement,HTMLHtmlElement:HTMLElement," +
    "HTMLHeadingElement:HTMLElement,HTMLHeadElement:HTMLElement,HTMLHRElement:HTMLElement," +
    "HTMLFrameSetElement:HTMLElement,HTMLFrameElement:HTMLElement,HTMLFormElement:HTMLElement," +
    "HTMLFormControlsCollection:HTMLCollection,HTMLFontElement:HTMLElement," +
    "HTMLFieldSetElement:HTMLElement,HTMLEmbedElement:HTMLElement,HTMLElement:Element," +
    "HTMLDocument:Document,HTMLDivElement:HTMLElement,HTMLDirectoryElement:HTMLElement," +
    "HTMLDialogElement:HTMLElement,HTMLDetailsElement:HTMLElement,HTMLDataListElement:HTMLElement," +
    "HTMLDataElement:HTMLElement,HTMLDListElement:HTMLElement,HTMLCollection," +
    "HTMLCanvasElement:HTMLElement,HTMLButtonElement:HTMLElement,HTMLBodyElement:HTMLElement," +
    "HTMLBaseElement:HTMLElement,HTMLBRElement:HTMLElement,HTMLAudioElement:HTMLMediaElement," +
    "HTMLAreaElement:HTMLElement,HTMLAnchorElement:HTMLElement,HTMLAllCollection," +
    "GeolocationPositionError,GeolocationPosition,GeolocationCoordinates,Geolocation," +
    "GamepadHapticActuator,GamepadEvent:Event:1,GamepadButton,Gamepad,GainNode:AudioNode:1," +
    "FormDataEvent:Event:2,FormData,FontFaceSetLoadEvent:Event:1,FontFaceSet:EventTarget,FontFace::2," +
    "FocusEvent:UIEvent:1,FileReader:EventTarget,FileList,File:Blob:2,External,EventTarget," +
    "EventSource:EventTarget:1,EventCounts,Event::1,ErrorEvent:Event:1,EncodedVideoChunk::1," +
    "EncodedAudioChunk::1,ElementInternals,Element:Node,EditContext:EventTarget," +
    "DynamicsCompressorNode:AudioNode:1,DragEvent:MouseEvent:1,DocumentType:Node," +
    "DocumentTimeline:AnimationTimeline,DocumentFragment:Node,Document:Node," +
    "DelegatedInkTrailPresenter,DelayNode:AudioNode:1,DecompressionStream::1,DataTransferItemList," +
    "DataTransferItem,DataTransfer,DOMTokenList,DOMStringMap,DOMStringList,DOMRectReadOnly," +
    "DOMRectList,DOMRect:DOMRectReadOnly,DOMQuad,DOMPointReadOnly,DOMPoint:DOMPointReadOnly," +
    "DOMParser,DOMMatrixReadOnly,DOMMatrix:DOMMatrixReadOnly,DOMImplementation,DOMException," +
    "DOMError::1,CustomStateSet,CustomEvent:Event:1,CustomElementRegistry,Crypto," +
    "CountQueuingStrategy::1,ConvolverNode:AudioNode:1,ContentVisibilityAutoStateChangeEvent:Event:1," +
    "ConstantSourceNode:AudioScheduledSourceNode:1,CompressionStream::1,CompositionEvent:UIEvent:1," +
    "Comment:CharacterData,CommandEvent:Event:1,CloseWatcher:EventTarget,CloseEvent:Event:1," +
    "ClipboardEvent:Event:1,CharacterData:Node,CharacterBoundsUpdateEvent:Event:1," +
    "ChannelSplitterNode:AudioNode:1,ChannelMergerNode:AudioNode:1,CaretPosition," +
    "CanvasRenderingContext2D,CanvasPattern,CanvasGradient," +
    "CanvasCaptureMediaStreamTrack:MediaStreamTrack,CSSViewTransitionRule:CSSRule," +
    "CSSVariableReferenceValue::1,CSSUnparsedValue:CSSStyleValue:1,CSSUnitValue:CSSNumericValue:2," +
    "CSSTranslate:CSSTransformComponent:2,CSSTransition:Animation,CSSTransformValue:CSSStyleValue:1," +
    "CSSTransformComponent,CSSSupportsRule:CSSConditionRule,CSSStyleValue,CSSStyleSheet:StyleSheet," +
    "CSSStyleRule:CSSRule,CSSStyleDeclaration,CSSStartingStyleRule:CSSGroupingRule," +
    "CSSSkewY:CSSTransformComponent:1,CSSSkewX:CSSTransformComponent:1," +
    "CSSSkew:CSSTransformComponent:2,CSSScopeRule:CSSGroupingRule,CSSScale:CSSTransformComponent:2," +
    "CSSRuleList,CSSRule,CSSRotate:CSSTransformComponent:1,CSSPropertyRule:CSSRule," +
    "CSSPositionValue:CSSStyleValue:2,CSSPositionTryRule:CSSRule," +
    "CSSPositionTryDescriptors:CSSStyleDeclaration,CSSPerspective:CSSTransformComponent:1," +
    "CSSPageRule:CSSGroupingRule,CSSNumericValue:CSSStyleValue,CSSNumericArray," +
    "CSSNestedDeclarations:CSSRule,CSSNamespaceRule:CSSRule,CSSMediaRule:CSSConditionRule," +
    "CSSMatrixComponent:CSSTransformComponent:1,CSSMathValue:CSSNumericValue,CSSMathSum:CSSMathValue," +
    "CSSMathProduct:CSSMathValue,CSSMathNegate:CSSMathValue:1,CSSMathMin:CSSMathValue," +
    "CSSMathMax:CSSMathValue,CSSMathInvert:CSSMathValue:1,CSSMathClamp:CSSMathValue:3," +
    "CSSMarginRule:CSSRule,CSSLayerStatementRule:CSSRule,CSSLayerBlockRule:CSSGroupingRule," +
    "CSSKeywordValue:CSSStyleValue:1,CSSKeyframesRule:CSSRule,CSSKeyframeRule:CSSRule," +
    "CSSImportRule:CSSRule,CSSImageValue:CSSStyleValue,CSSGroupingRule:CSSRule," +
    "CSSFontPaletteValuesRule:CSSRule,CSSFontFaceRule:CSSRule,CSSCounterStyleRule:CSSRule," +
    "CSSContainerRule:CSSConditionRule,CSSConditionRule:CSSGroupingRule,CSSAnimation:Animation," +
    "CSPViolationReportBody:ReportBody,CDATASection:Text,ByteLengthQueuingStrategy::1," +
    "BrowserCaptureMediaStreamTrack:MediaStreamTrack,BroadcastChannel:EventTarget:1," +
    "BlobEvent:Event:2,Blob,BiquadFilterNode:AudioNode:1,BeforeUnloadEvent:Event," +
    "BeforeInstallPromptEvent:Event:1,BaseAudioContext:EventTarget,BarProp," +
    "AudioWorkletNode:AudioNode:2,AudioSinkInfo,AudioScheduledSourceNode:AudioNode," +
    "AudioProcessingEvent:Event:2,AudioParamMap,AudioParam,AudioNode:EventTarget,AudioListener," +
    "AudioDestinationNode:AudioNode,AudioData::1,AudioContext:BaseAudioContext," +
    "AudioBufferSourceNode:AudioScheduledSourceNode:1,AudioBuffer::1,Attr:Node,AnimationTimeline," +
    "AnimationPlaybackEvent:Event:1,AnimationEvent:Event:1,AnimationEffect,Animation:EventTarget," +
    "AnalyserNode:AudioNode:1,AbstractRange,AbortSignal:EventTarget,AbortController," +
    "AbsoluteOrientationSensor:OrientationSensor,Accelerometer:Sensor,AudioDecoder:EventTarget:1," +
    "AudioEncoder:EventTarget:1,AudioWorklet:Worklet,BatteryManager:EventTarget,Cache,CacheStorage," +
    "Clipboard:EventTarget,ClipboardChangeEvent:Event,ClipboardItem::1,CookieChangeEvent:Event:1," +
    "CookieStore:EventTarget,CookieStoreManager,CreateMonitor:EventTarget,Credential," +
    "CredentialsContainer,CryptoKey,DeviceMotionEvent:Event:1,DeviceMotionEventAcceleration," +
    "DeviceMotionEventRotationRate,DeviceOrientationEvent:Event:1,FederatedCredential:Credential:1," +
    "GPU,GPUAdapter,GPUAdapterInfo,GPUBindGroup,GPUBindGroupLayout,GPUBuffer,GPUCanvasContext," +
    "GPUCommandBuffer,GPUCommandEncoder,GPUCompilationInfo,GPUCompilationMessage," +
    "GPUComputePassEncoder,GPUComputePipeline,GPUDevice:EventTarget,GPUDeviceLostInfo,GPUError," +
    "GPUExternalTexture,GPUInternalError:GPUError:1,GPUOutOfMemoryError:GPUError:1," +
    "GPUPipelineError:DOMException:1,GPUPipelineLayout,GPUQuerySet,GPUQueue,GPURenderBundle," +
    "GPURenderBundleEncoder,GPURenderPassEncoder,GPURenderPipeline,GPUSampler,GPUShaderModule," +
    "GPUSupportedFeatures,GPUSupportedLimits,GPUTexture,GPUTextureView," +
    "GPUUncapturedErrorEvent:Event:2,GPUValidationError:GPUError:1,GravitySensor:Accelerometer," +
    "Gyroscope:Sensor,IdleDetector:EventTarget,ImageCapture::1,ImageDecoder::1,ImageTrack," +
    "ImageTrackList,Keyboard,KeyboardLayoutMap,LinearAccelerationSensor:Accelerometer," +
    "MIDIAccess:EventTarget,MIDIConnectionEvent:Event:1,MIDIInput:MIDIPort,MIDIInputMap," +
    "MIDIMessageEvent:Event:1,MIDIOutput:MIDIPort,MIDIOutputMap,MIDIPort:EventTarget,MediaDeviceInfo," +
    "MediaDevices:EventTarget,MediaKeyMessageEvent:Event:2,MediaKeySession:EventTarget," +
    "MediaKeyStatusMap,MediaKeySystemAccess,MediaKeys,NavigationPreloadManager," +
    "NavigatorManagedData:EventTarget,OrientationSensor:Sensor,PasswordCredential:Credential:1," +
    "ProtectedAudience,RelativeOrientationSensor:OrientationSensor,ScreenDetailed:Screen," +
    "ScreenDetails:EventTarget,Sensor:EventTarget,SensorErrorEvent:Event:2," +
    "ServiceWorkerRegistration:EventTarget,StorageManager,SubtleCrypto,VideoDecoder:EventTarget:1," +
    "VideoEncoder:EventTarget:1,VirtualKeyboard:EventTarget,WGSLLanguageFeatures,WebTransport::1," +
    "WebTransportBidirectionalStream,WebTransportDatagramDuplexStream,WebTransportError:DOMException," +
    "Worklet,XRDOMOverlayState,XRLayer:EventTarget,XRWebGLBinding::2,AudioPlaybackStats," +
    "AuthenticatorAssertionResponse:AuthenticatorResponse," +
    "AuthenticatorAttestationResponse:AuthenticatorResponse,AuthenticatorResponse," +
    "PublicKeyCredential:Credential,BarcodeDetector,Bluetooth:EventTarget," +
    "BluetoothCharacteristicProperties,BluetoothDevice:EventTarget," +
    "BluetoothRemoteGATTCharacteristic:EventTarget,BluetoothRemoteGATTDescriptor," +
    "BluetoothRemoteGATTServer,BluetoothRemoteGATTService,CaptureController:EventTarget," +
    "CrashReportContext,DevicePosture:EventTarget,DigitalCredential:Credential," +
    "DocumentPictureInPicture:EventTarget,EyeDropper,FetchLaterResult," +
    "FileSystemDirectoryHandle:FileSystemHandle,FileSystemFileHandle:FileSystemHandle," +
    "FileSystemHandle,FileSystemWritableFileStream:WritableStream,FileSystemObserver::1,FontData," +
    "FragmentDirective,HID:EventTarget,HIDConnectionEvent:Event:2,HIDDevice:EventTarget," +
    "HIDInputReportEvent:Event,IdentityCredential:Credential,IdentityCredentialError:DOMException," +
    "IdentityProvider,NavigatorLogin,LanguageDetector,LanguageModel:EventTarget,Lock,LockManager," +
    "ServiceWorker:EventTarget,ServiceWorkerContainer:EventTarget,NotRestoredReasonDetails," +
    "NotRestoredReasons,OTPCredential:Credential,PaymentAddress,PaymentRequest:EventTarget:1," +
    "PaymentRequestUpdateEvent:Event:1,PaymentResponse:EventTarget,PaymentManager," +
    "PaymentMethodChangeEvent:PaymentRequestUpdateEvent:1,Presentation," +
    "PresentationAvailability:EventTarget,PresentationConnection:EventTarget," +
    "PresentationConnectionAvailableEvent:Event:2,PresentationConnectionCloseEvent:Event:2," +
    "PresentationConnectionList:EventTarget,PresentationReceiver,PresentationRequest:EventTarget:1," +
    "PressureObserver::1,PressureRecord,Serial:EventTarget,SerialPort:EventTarget," +
    "SpeechRecognitionPhrase::1,StorageBucket,StorageBucketManager,Summarizer,Translator," +
    "USB:EventTarget,USBAlternateInterface::2,USBConfiguration::2,USBConnectionEvent:Event:2," +
    "USBDevice,USBEndpoint::3,USBInTransferResult::1,USBInterface::2," +
    "USBIsochronousInTransferPacket::1,USBIsochronousInTransferResult::1," +
    "USBIsochronousOutTransferPacket::1,USBIsochronousOutTransferResult::1,USBOutTransferResult::1," +
    "WakeLock,WakeLockSentinel:EventTarget,XRAnchor,XRAnchorSet," +
    "XRBoundedReferenceSpace:XRReferenceSpace,XRCPUDepthInformation:XRDepthInformation,XRCamera," +
    "XRDepthInformation,XRFrame,XRHand,XRHitTestResult,XRHitTestSource,XRInputSource," +
    "XRInputSourceArray,XRInputSourceEvent:Event:2,XRInputSourcesChangeEvent:Event:2," +
    "XRJointPose:XRPose,XRJointSpace:XRSpace,XRLightEstimate,XRLightProbe:EventTarget,XRPose,XRRay," +
    "XRReferenceSpace:XRSpace,XRReferenceSpaceEvent:Event:2,XRRenderState,XRRigidTransform," +
    "XRSession:EventTarget,XRSessionEvent:Event:2,XRSpace:EventTarget,XRSystem:EventTarget," +
    "XRTransientInputHitTestResult,XRTransientInputHitTestSource,XRView,XRViewerPose:XRPose," +
    "XRViewport,XRWebGLDepthInformation:XRDepthInformation,XRWebGLLayer:XRLayer:2," +
    "XRCompositionLayer:XRLayer,XRProjectionLayer:XRCompositionLayer,XRCubeLayer:XRCompositionLayer," +
    "XRCylinderLayer:XRCompositionLayer,XREquirectLayer:XRCompositionLayer,XRLayerEvent:Event:2," +
    "XRQuadLayer:XRCompositionLayer,XRSubImage,XRWebGLSubImage:XRSubImage,XRPlane,XRPlaneSet," +
    "XRVisibilityMaskChangeEvent:Event:2,XSLTProcessor,AnimationTrigger,BackgroundFetchManager," +
    "BackgroundFetchRecord,BackgroundFetchRegistration:EventTarget,BluetoothUUID," +
    "CSSFontFeatureValuesRule:CSSRule,CSSFunctionDeclarations:CSSRule," +
    "CSSFunctionDescriptors:CSSStyleDeclaration,CSSFunctionRule:CSSGroupingRule,CSSPseudoElement," +
    "ChapterInformation,CropTarget,DocumentPictureInPictureEvent:Event:2,Fence,FencedFrameConfig," +
    "HTMLFencedFrameElement:HTMLElement,HTMLCameraElement:HTMLElement," +
    "HTMLMicrophoneElement:HTMLElement,HTMLGeolocationElement:HTMLElement," +
    "HTMLUserMediaElement:HTMLElement,IntegrityViolationReportBody:ReportBody,LaunchParams," +
    "LaunchQueue,MediaMetadata,MediaSession,NodeRange:AbstractRange,OpaqueRange:AbstractRange," +
    "Notification:EventTarget:1,Origin,PageSwapEvent:Event:1," +
    "PerformanceLongAnimationFrameTiming:PerformanceEntry,PerformanceScriptTiming:PerformanceEntry," +
    "RTCDataChannel:EventTarget,PeriodicSyncManager,Profiler:EventTarget:1,PushManager," +
    "PushSubscription,PushSubscriptionOptions,QuotaExceededError:DOMException," +
    "RTCRtpScriptTransform::1,RemotePlayback:EventTarget,RestrictionTarget,Sanitizer," +
    "SharedWorker:EventTarget:1,SnapEvent:Event,SpeechGrammar,SpeechGrammarList," +
    "SpeechRecognition:EventTarget,SpeechRecognitionErrorEvent:Event:1," +
    "SpeechRecognitionEvent:Event:1,SpeechSynthesis:EventTarget," +
    "SpeechSynthesisErrorEvent:SpeechSynthesisEvent:2,SpeechSynthesisEvent:Event:2," +
    "SpeechSynthesisUtterance:EventTarget,SpeechSynthesisVoice,TimelineTrigger:AnimationTrigger," +
    "TimelineTriggerRange,TimelineTriggerRangeList,Viewport,WebSocketError:DOMException," +
    "WebSocketStream::1";
  var NOT_CHROME = [
    "InternalError",
    "SharedArrayBuffer",
    "AudioTrack",
    "AudioTrackList",
    "VideoTrack",
    "VideoTrackList",
    "CSSPageDescriptors",
    "CSSStyleProperties",
  ];

  function stub(name, parentName, length) {
    var parent = parentName ? globalThis[parentName] : null;
    var ctor = __nativeFn(
      name,
      function () {
        throw new TypeError("Illegal constructor");
      },
      length,
    );
    var proto = Object.create(
      typeof parent === "function" && parent.prototype ? parent.prototype : Object.prototype,
    );
    Object.defineProperty(proto, "constructor", {
      value: ctor,
      writable: true,
      enumerable: false,
      configurable: true,
    });
    Object.defineProperty(proto, Symbol.toStringTag, { value: name, configurable: true });
    Object.defineProperty(ctor, "prototype", { value: proto, writable: false });
    if (typeof parent === "function") Object.setPrototypeOf(ctor, parent);
    Object.defineProperty(globalThis, name, {
      value: ctor,
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  var rows = CHROME.split(",");
  var pending = [];
  var i, parts, name, parentName, progressed;
  for (i = 0; i < rows.length; i++) {
    parts = rows[i].split(":");
    if (typeof globalThis[parts[0]] === "undefined") pending.push(parts);
  }
  /* Parents first: a stub's prototype chain is fixed at creation. */
  do {
    progressed = false;
    for (i = 0; i < pending.length; i++) {
      parts = pending[i];
      if (!parts) continue;
      name = parts[0];
      parentName = parts[1] || "";
      if (
        parentName &&
        parentName !== "TypedArray" &&
        typeof globalThis[parentName] === "undefined"
      ) {
        var waiting = false,
          j;
        for (j = 0; j < pending.length; j++)
          if (pending[j] && pending[j][0] === parentName) waiting = true;
        if (waiting) continue;
      }
      stub(name, parentName === "TypedArray" ? "" : parentName, parts[2] ? +parts[2] : 0);
      pending[i] = null;
      progressed = true;
    }
  } while (progressed);
  for (i = 0; i < NOT_CHROME.length; i++) delete globalThis[NOT_CHROME[i]];
})();
